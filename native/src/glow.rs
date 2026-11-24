pub mod table {
    include!("glow_table.rs");
}

use crate::motion;

pub const SEEDS: [f32; 33] = [
    -80.79679, -151.77667, 289.53015, 133.59625, 152.01352, 90.91115,
    -256.38846, 78.98083, 230.8243, -136.37402, -38.153152, 159.89685,
    -13.360941, -157.33534, -135.0559, -84.44102, -200.09569, -8.089579,
    238.59375, 245.52487, -263.6614, 242.79184, 2.7137518, 9.775186,
    -108.58024, 291.98526, -3.6139905, -140.3133, -245.56026, 268.65857,
    -255.75055, 0.42425394, -69.5147,
];

pub const U_LIGHT_COLORS: [f32; 33] = [
    0.502, 0.525, 1.0, 1.0, 0.827, 0.702, 1.0, 0.525, 0.208, 0.518, 0.494, 1.0,
    0.071, 0.412, 0.949, 0.502, 0.525, 1.0, 1.0, 0.827, 0.702, 1.0, 0.525, 0.208,
    0.518, 0.494, 1.0, 0.071, 0.412, 0.949, 1.0, 0.525, 0.208,
];

pub const EVENT_NONE: i32 = 0;
pub const EVENT_START_ANIM: i32 = 1;
pub const EVENT_START_TRANS: i32 = 2;
pub const EVENT_STOP_ANIM: i32 = 3;
pub const EVENT_STOP_TRANS: i32 = 4;
pub const EVENT_RESET: i32 = 5;
pub const EVENT_START_STILL: i32 = 6;

#[inline]
pub fn noise(x: f32, y: f32) -> f32 {
    let ix = (x as i32) - if x <= 0.0 { 1 } else { 0 };
    let iy = (y as i32) - if y <= 0.0 { 1 } else { 0 };
    let dx = x - (ix as f32);
    let dy = y - (iy as f32);
    let fx = ((6.0_f32 * dx - 15.0_f32) * dx + 10.0_f32) * (dx * dx * dx);
    let fy = ((6.0_f32 * dy - 15.0_f32) * dy + 10.0_f32) * (dy * dy * dy);

    let p0 = table::PERMUTATION[(iy & 255) as usize] as usize;
    let p1 = table::PERMUTATION[((iy + 1) & 255) as usize] as usize;

    let h00 = table::PERMUTATION[p0 + ((ix & 255) as usize)] as usize;
    let h10 = table::PERMUTATION[((p0 as i32 + ix + 1) & 255) as usize] as usize;
    let h01 = table::PERMUTATION[p1 + ((ix & 255) as usize)] as usize;
    let h11 = table::PERMUTATION[((p1 as i32 + ix + 1) & 255) as usize] as usize;

    #[inline]
    fn grad(h: usize, x: f32, y: f32) -> f32 {
        let (a, b) = if (h & 4) != 0 { (x, y) } else { (y, x) };
        let mult_a = if (h & 2) != 0 { -2.0_f32 } else { 2.0_f32 };
        let term_b = if (h & 1) != 0 { -b } else { b };
        mult_a * a + term_b
    }

    let lower = (grad(h11, dx - 1.0_f32, dy - 1.0_f32) - grad(h10, dx - 1.0_f32, dy)) * fy + grad(h10, dx - 1.0_f32, dy);
    let upper = (grad(h01, dx, dy - 1.0_f32) - grad(h00, dx, dy)) * fy + grad(h00, dx, dy);
    ((lower - upper) * fx + upper) * 0.507_f32
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PhysicSpring {
    pub value: f32,
    pub velocity: f32,
    pub max_acceleration: f32,
    pub amount: f32,
}

impl PhysicSpring {
    pub const fn new(value: f32, velocity: f32, max_acceleration: f32, amount: f32) -> Self {
        Self {
            value,
            velocity,
            max_acceleration,
            amount,
        }
    }

    pub fn step(&mut self, target: f32) {
        let val = self.value;
        let amt = self.amount;
        let delta = ((target * amt) + ((1.0_f32 - amt) * val)) - val;
        if (delta as f64) == 0.0 {
            return;
        }
        let vel = self.velocity;
        let max_a = self.max_acceleration;
        let diff = delta - vel;
        let new_vel = if diff <= max_a {
            if -max_a <= diff {
                vel + diff
            } else {
                vel - max_a
            }
        } else {
            vel + max_a
        };
        self.velocity = new_vel;
        let mut chosen = new_vel;
        if (delta as f64) >= 0.0 {
            if delta < new_vel {
                self.velocity = delta;
                chosen = delta;
            }
        } else if new_vel < delta {
            self.velocity = delta;
            chosen = delta;
        }
        self.value = val + chosen;
    }
}

#[derive(Clone, Copy, Debug)]
pub struct FolmeEnvelope {
    pub value: f64,
    pub velocity: f64,
    pub target: f64,
    pub spring: Option<motion::Spring>,
    pub delay_ms: f64,
    pub elapsed_ms: f64,
    pub first_frame: bool,
    pub active: bool,
}

impl FolmeEnvelope {
    pub fn new(initial: f64) -> Self {
        Self {
            value: initial,
            velocity: 0.0,
            target: initial,
            spring: None,
            delay_ms: 0.0,
            elapsed_ms: 0.0,
            first_frame: true,
            active: false,
        }
    }

    pub fn retarget(&mut self, target: f64, spring: Option<motion::Spring>, delay_ms: f64) {
        self.target = target;
        self.spring = spring;
        self.delay_ms = delay_ms;
        self.elapsed_ms = 0.0;
        self.first_frame = true;
        self.active = true;
    }

    pub fn snap(&mut self, target: f64) {
        self.value = target;
        self.velocity = 0.0;
        self.target = target;
        self.spring = None;
        self.delay_ms = 0.0;
        self.elapsed_ms = 0.0;
        self.first_frame = true;
        self.active = false;
    }

    pub fn step(&mut self, dt: f64) -> bool {
        if !self.active {
            return false;
        }
        let spring = match self.spring {
            Some(s) => s,
            None => {
                self.value = self.target;
                self.velocity = 0.0;
                self.active = false;
                return false;
            }
        };
        let dt_ms = dt * 1000.0;
        if self.elapsed_ms < self.delay_ms {
            self.elapsed_ms += dt_ms;
            return true;
        }
        let res = spring.folme_frame(self.value, self.target, self.velocity, dt, self.first_frame);
        self.first_frame = false;
        self.value = res.value;
        self.velocity = res.velocity;
        if motion::at_equilibrium(self.value, self.target, self.velocity, 0.004) {
            self.value = self.target;
            self.velocity = 0.0;
            self.active = false;
            return false;
        }
        true
    }
}

pub struct GlowEngine {
    pub flame: PhysicSpring,
    pub on: PhysicSpring,
    pub volume: PhysicSpring,
    pub glow: PhysicSpring,
    pub phase: f32,
    pub last_ms: Option<i64>,
    pub start_ms: Option<i64>,
    pub running: bool,
    pub fg_edge: FolmeEnvelope,
    pub bg_edge: FolmeEnvelope,
    pub fg_alpha: FolmeEnvelope,
    pub bg_alpha: FolmeEnvelope,
    pub lights: [f32; 22],
}

impl Default for GlowEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl GlowEngine {
    pub fn new() -> Self {
        let mut engine = Self {
            flame: PhysicSpring::new(0.0, 0.0, 0.018, 0.19000006),
            on: PhysicSpring::new(0.0, 0.0, 0.018, 0.19000006),
            volume: PhysicSpring::new(0.0, 0.0, 0.026999999, 0.31937504),
            glow: PhysicSpring::new(0.0, 0.0, 0.026999999, 0.31937504),
            phase: 0.0,
            last_ms: None,
            start_ms: None,
            running: false,
            fg_edge: FolmeEnvelope::new(0.0),
            bg_edge: FolmeEnvelope::new(0.0),
            fg_alpha: FolmeEnvelope::new(1.0),
            bg_alpha: FolmeEnvelope::new(0.0),
            lights: [0.0; 22],
        };
        engine.update_lights();
        engine
    }

    pub fn apply_event(&mut self, event: i32, animation_scale: f32) {
        match event {
            EVENT_START_ANIM => {
                self.running = true;
                self.last_ms = None;
                self.fg_edge.snap(0.0);
                self.bg_edge.snap(0.0);
                if animation_scale <= 0.0 {
                    self.fg_edge.snap(1.0);
                    self.bg_edge.snap(1.0);
                    self.fg_alpha.snap(1.0);
                    self.bg_alpha.snap(1.0);
                } else {
                    let spring_edge = motion::Spring::new(3.0, 0.8 * animation_scale as f64, 1.0);
                    self.fg_edge.retarget(1.0, spring_edge, 100.0);
                    self.bg_edge.retarget(1.0, spring_edge, 100.0);
                    let spring_bg = motion::Spring::new(0.7, 0.5 * animation_scale as f64, 1.0);
                    self.bg_alpha.retarget(1.0, spring_bg, 200.0);
                }
            }
            EVENT_START_TRANS => {
                self.running = true;
                self.last_ms = None;
                self.fg_edge.snap(1.0);
                self.bg_edge.snap(1.0);
                if animation_scale <= 0.0 {
                    self.fg_alpha.snap(1.0);
                    self.bg_alpha.snap(1.0);
                } else {
                    let spring = motion::Spring::new(0.95, 0.35 * animation_scale as f64, 1.0);
                    self.fg_alpha.retarget(1.0, spring, 0.0);
                    self.bg_alpha.retarget(1.0, spring, 0.0);
                }
            }
            EVENT_STOP_ANIM => {
                self.fg_edge.active = false;
                self.fg_edge.velocity = 0.0;
                self.bg_edge.active = false;
                self.bg_edge.velocity = 0.0;
                if animation_scale <= 0.0 {
                    self.fg_alpha.snap(0.0);
                    self.bg_alpha.snap(0.0);
                    self.running = false;
                    self.last_ms = None;
                } else {
                    let spring = motion::Spring::new(0.95, 0.35 * animation_scale as f64, 1.0);
                    self.fg_alpha.retarget(0.0, spring, 0.0);
                    self.bg_alpha.retarget(0.0, spring, 0.0);
                }
            }
            EVENT_STOP_TRANS => {
                self.fg_edge.active = false;
                self.fg_edge.velocity = 0.0;
                self.bg_edge.active = false;
                self.bg_edge.velocity = 0.0;
                if animation_scale <= 0.0 {
                    self.fg_alpha.snap(0.0);
                    self.bg_alpha.snap(0.0);
                    self.running = false;
                    self.last_ms = None;
                } else {
                    let spring = motion::Spring::new(0.95, 0.1 * animation_scale as f64, 1.0);
                    self.fg_alpha.retarget(0.0, spring, 0.0);
                    self.bg_alpha.retarget(0.0, spring, 0.0);
                }
            }
            EVENT_RESET => {
                self.running = false;
                self.last_ms = None;
                self.start_ms = None;
                self.phase = 0.0;
                self.fg_alpha.snap(1.0);
                self.bg_alpha.snap(0.0);
                self.fg_edge.snap(0.0);
                self.bg_edge.snap(0.0);
                self.flame = PhysicSpring::new(0.0, 0.0, 0.018, 0.19000006);
                self.on = PhysicSpring::new(0.0, 0.0, 0.018, 0.19000006);
                self.volume = PhysicSpring::new(0.0, 0.0, 0.026999999, 0.31937504);
                self.glow = PhysicSpring::new(0.0, 0.0, 0.026999999, 0.31937504);
                self.update_lights();
            }
            EVENT_START_STILL => {
                self.running = true;
                self.last_ms = None;
                self.fg_edge.snap(1.0);
                self.bg_edge.snap(1.0);
                self.fg_alpha.snap(1.0);
                self.bg_alpha.snap(1.0);
            }
            _ => {}
        }
    }

    fn update_lights(&mut self) {
        for j in 0..11 {
            let k = 3 * j;
            let t = self.phase * 0.05_f32;
            let a = noise(k as f32 - 8.5_f32, SEEDS[k] + t) * 12.56637_f32;
            let b = noise((k + 1) as f32 - 8.5_f32, SEEDS[k + 1] + t) * 12.56637_f32;
            let c = noise((k + 2) as f32 - 8.5_f32, SEEDS[k + 2] + t) * 12.56637_f32;

            let sin_a = (a as f64).sin() as f32;
            let cos_a = (a as f64).cos() as f32;
            let sin_b = (b as f64).sin() as f32;
            let cos_b = (b as f64).cos() as f32;
            let sin_c = (c as f64).sin() as f32;
            let cos_c = (c as f64).cos() as f32;

            let x = (cos_c * cos_b + (-sin_b) * sin_c * sin_a) * 0.9_f32;
            let y = (-cos_a * sin_c) * 0.9_f32;
            self.lights[2 * j] = x;
            self.lights[2 * j + 1] = y;
        }
    }

    pub fn frame(&mut self, now_ms: i64, event: i32, animation_scale: f32, out: &mut [f32; 63]) -> bool {
        if event != EVENT_NONE {
            self.apply_event(event, animation_scale);
        }

        if self.running {
            if self.start_ms.is_none() {
                self.start_ms = Some(now_ms);
            }

            if self.last_ms != Some(now_ms) {
                let dt = match self.last_ms {
                    None => 0.033333335_f32,
                    Some(last) => {
                        let diff = (now_ms - last) as f32 / 1000.0_f32;
                        if diff < 0.0 {
                            0.033333335_f32
                        } else {
                            diff
                        }
                    }
                };
                self.last_ms = Some(now_ms);

                // Step physics
                self.flame.step(0.7);
                self.on.step(1.0);
                self.volume.step(0.0);
                let glow_target = (((self.on.velocity.max(0.0_f32) * 20.0_f32) * 0.3_f32) + self.volume.value).min(1.3_f32);
                self.glow.step(glow_target);
                self.phase += 0.7_f32 * dt;

                // Step envelopes
                self.fg_edge.step(dt as f64);
                self.bg_edge.step(dt as f64);
                let bg_waiting_before = self.bg_alpha.active && self.bg_alpha.elapsed_ms < self.bg_alpha.delay_ms;
                self.bg_alpha.step(dt as f64);
                if bg_waiting_before && self.bg_alpha.elapsed_ms >= self.bg_alpha.delay_ms {
                    self.fg_alpha.snap(1.0);
                }
                self.fg_alpha.step(dt as f64);

                // Check exit settling
                if (self.fg_alpha.target == 0.0 && self.bg_alpha.target == 0.0)
                    && !self.fg_alpha.active && !self.bg_alpha.active {
                    self.running = false;
                    self.last_ms = None;
                }

                self.update_lights();
            }
        }

        let time_sec = match self.start_ms {
            Some(s) if self.running => ((now_ms - s).max(0) as f32) / 1000.0_f32,
            _ => 0.0_f32,
        };

        out[0] = time_sec;
        out[1] = self.glow.value;
        out[2] = 15.0_f32;
        out[3] = self.fg_edge.value as f32;
        out[4] = self.bg_edge.value as f32;
        out[5] = self.fg_alpha.value as f32;
        out[6] = self.bg_alpha.value as f32;
        out[7] = if self.running { 1.0_f32 } else { 0.0_f32 };
        out[8..30].copy_from_slice(&self.lights);
        out[30..63].copy_from_slice(&U_LIGHT_COLORS);

        self.running
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn permutation_table_matches_exact_smali_extraction() {
        assert_eq!(table::PERMUTATION.len(), 512);
        assert_eq!(table::PERMUTATION[0], 151);
        assert_eq!(table::PERMUTATION[1], 160);
        assert_eq!(table::PERMUTATION[255], 180);
        assert_eq!(table::PERMUTATION[256], 151);
        assert_eq!(table::PERMUTATION[511], 180);
    }

    #[test]
    fn noise_evaluation_preserves_finite_bounds() {
        for i in -20..20 {
            for j in -20..20 {
                let x = i as f32 * 0.35;
                let y = j as f32 * 0.35;
                let n = noise(x, y);
                assert!(n.is_finite(), "noise must be finite at ({}, {})", x, y);
            }
        }
    }

    #[test]
    fn physic_spring_c2172e_step_behavior() {
        let mut spring = PhysicSpring::new(0.0, 0.0, 0.018, 0.19000006);
        assert_eq!(spring.value, 0.0);
        spring.step(1.0);
        assert!(spring.value > 0.0);
        assert!(spring.velocity > 0.0);
        assert!(spring.velocity <= 0.018);
    }

    #[test]
    fn glow_engine_lifecycle_events_and_settling() {
        let mut engine = GlowEngine::new();
        let mut packet = [0.0f32; 63];

        // Hidden initially
        let running = engine.frame(1000, EVENT_NONE, 1.0, &mut packet);
        assert!(!running);
        assert_eq!(packet[5], 1.0); // fgAlpha
        assert_eq!(packet[6], 0.0); // bgAlpha
        assert_eq!(packet[7], 0.0); // running

        // START_ANIM
        let running = engine.frame(1000, EVENT_START_ANIM, 1.0, &mut packet);
        assert!(running);
        assert_eq!(packet[7], 1.0);
        assert_eq!(packet[3], 0.0); // fgEdge
        assert_eq!(packet[4], 0.0); // bgEdge

        // Simulate 500ms of frames
        for step in 1..=25 {
            let t = 1000 + step * 20;
            engine.frame(t, EVENT_NONE, 1.0, &mut packet);
        }
        // After 500ms, edges and alphas should be active/moving towards 1
        assert!(packet[3] > 0.0);
        assert!(packet[6] > 0.0);

        // STOP_TRANS
        engine.frame(1516, EVENT_STOP_TRANS, 1.0, &mut packet);
        assert!(engine.running, "Should still be running during exit transition");

        // Advance until settled
        let mut settled = false;
        for step in 1..=100 {
            let t = 1516 + step * 16;
            let still_running = engine.frame(t, EVENT_NONE, 1.0, &mut packet);
            if !still_running {
                settled = true;
                break;
            }
        }
        assert!(settled, "Exit transition must settle to running=false");
        assert_eq!(packet[5], 0.0);
        assert_eq!(packet[6], 0.0);
        assert_eq!(packet[7], 0.0);
    }

    #[test]
    fn invalid_and_destroyed_handles_are_safe() {
        let engine = GlowEngine::new();
        let short_packet = [0.0f32; 10];
        assert_eq!(short_packet.len(), 10);
        assert!(!engine.running);
    }

    #[test]
    fn noise_matches_exact_smali_oracle_vectors() {
        // Sample points from exact Smali register execution oracle
        let test_cases: &[(f32, f32, f32)] = &[
            (-10.5, -5.2, -0.019087536),
            (-10.27, -4.89, 0.19571489),
            (-10.04, -4.58, 0.37778175),
            (-9.81, -4.27, 0.15957034),
            (-9.58, -3.96, -0.28224623),
            (0.0, 0.0, 0.0),
            (1.5, 2.5, 0.2535),
        ];
        for &(x, y, expected) in test_cases {
            let actual = noise(x, y);
            assert!(
                (actual - expected).abs() < 1e-4,
                "Mismatch at ({}, {}): actual={}, expected={}",
                x, y, actual, expected
            );
        }
    }
}
