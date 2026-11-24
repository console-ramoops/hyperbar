//! HyperOS Folme spring arithmetic recovered from MIUISystemUIPlugin.apk.
//!
//! Source: miuix.animation.physics.SpringOperator (classes2.dex) and
//! miui.systemui.dynamicisland.anim.DynamicIslandAnimationDelegate.
//! `step` retains the original f64 operation order. `folme_frame` additionally
//! retains FolmeCore's first-frame velocity-only warm-up. Geometry, delays and
//! Android view ownership remain the caller's responsibility.

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SpringState {
    pub value: f64,
    pub velocity: f64,
}

#[derive(Clone, Copy, Debug)]
pub struct Spring {
    pub damping: f64,
    pub response: f64,
    pub mass: f64,
    pub stiffness: f64,
    pub friction: f64,
    pub frequency: f64,
}

impl Spring {
    pub fn new(damping: f64, response: f64, mass: f64) -> Option<Self> {
        if !damping.is_finite() || damping < 0.0 || !response.is_finite()
            || response <= 0.0 || !mass.is_finite() || mass <= 0.0 {
            return None;
        }
        // Donor SpringOperator.getParameters, smali2 lines 223..324.
        let omega = 6.283185307179586_f64 / response;
        let stiffness = omega.powf(2.0) * mass;
        let friction = 2.0 * omega * damping * mass;
        let frequency = if damping > 1.0 {
            (damping * damping - 1.0).sqrt() * omega
        } else if damping < 1.0 {
            (1.0 - damping * damping).sqrt() * omega
        } else {
            0.0
        };
        if !stiffness.is_finite() || !friction.is_finite() || !frequency.is_finite() {
            return None;
        }
        Some(Self { damping, response, mass, stiffness, friction, frequency })
    }

    /// Exact closed-form update of the shipped SpringOperator.
    pub fn step(self, value: f64, target: f64, velocity: f64, dt: f64) -> SpringState {
        let displacement = value - target;
        let decay = (self.friction * -0.5 * dt).exp();
        let offset;
        let speed;
        if self.damping < 1.0 {
            let coefficient = ((self.friction * 0.5) * displacement + velocity) / self.frequency;
            let phase = self.frequency * dt;
            let cosine = phase.cos();
            let sine = phase.sin();
            offset = (coefficient * sine + displacement * cosine) * decay;
            speed = ((coefficient * cosine - displacement * sine) * self.frequency * decay)
                + (-0.5 * offset * self.friction);
        } else if self.damping == 1.0 {
            let coefficient = (self.friction * 0.5 * displacement) + velocity;
            offset = ((coefficient * dt) + displacement) * decay;
            speed = (coefficient * decay) + (-0.5 * offset * self.friction);
        } else {
            let coefficient = ((self.friction * 0.5) * displacement + velocity) / self.frequency;
            let phase = self.frequency * dt;
            let cosine = phase.cosh();
            let sine = phase.sinh();
            offset = (coefficient * sine + displacement * cosine) * decay;
            speed = ((displacement * sine + coefficient * cosine) * self.frequency * decay)
                + (-0.5 * offset * self.friction);
        }
        SpringState { value: offset + target, velocity: speed }
    }

    /// FolmeCore.doPhysicsCalculation runs one extra spring update on frame1,
    /// then restores only the position while retaining the new velocity.
    pub fn folme_frame(self, value: f64, target: f64, velocity: f64, dt: f64,
        first_frame: bool) -> SpringState {
        let speed = if first_frame { self.step(value,target,velocity,dt).velocity } else { velocity };
        self.step(value,target,speed,dt)
    }
}

/// IDs match the accompanying donor/motion/spec.json.
pub const CHANGE: u32 = 0;
pub const SHOW: u32 = 1;
pub const HIDDEN: u32 = 2;
pub const APPEAR: u32 = 3;
pub const SCALE: u32 = 4;
pub const ALPHA: u32 = 5;
pub const FAKE_ALPHA: u32 = 6;

/// Multiplication uses f32 before promotion, matching Kotlin Float arithmetic
/// in DynamicIslandAnimationDelegate.java:4995..5015.
pub fn preset(id: u32, animation_scale: f32) -> Option<Spring> {
    if !animation_scale.is_finite() || animation_scale <= 0.0 { return None; }
    let (damping,response): (f32,f32) = match id {
        CHANGE => (0.82, 0.4 * animation_scale),
        SHOW => (0.95, 0.35 * animation_scale),
        HIDDEN => (1.0, 0.2 * animation_scale),
        APPEAR => (0.7, 0.5 * animation_scale),
        SCALE => (1.0, animation_scale * 1.0),
        ALPHA => (0.95, 0.15 * animation_scale),
        FAKE_ALPHA => (1.0, 0.1 * animation_scale),
        _ => return None,
    };
    Spring::new(damping as f64,response as f64,1.0)
}

/// EquilibriumChecker checks both position and velocity strictly below their
/// thresholds. The caller supplies the property's minVisibleChange.
pub fn at_equilibrium(value: f64, target: f64, velocity: f64,
    min_visible_change: f32) -> bool {
    let threshold = min_visible_change * 0.75;
    let velocity_threshold = threshold * 16.666666_f32;
    (value-target).abs() < threshold as f64 && velocity.abs() < velocity_threshold as f64
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ChannelConfig {
    pub preset: u32,
    pub delay_ms: u32,
    /// A nonzero duration selects a linear channel instead of a spring.
    pub linear_duration_ms: u32,
}

// Channel IDs use the original IslandAnimProperties constructor order.
pub const CONTAINER_BLUR: u32 = 0;
pub const CONTAINER_X: u32 = 1;
pub const CONTAINER_TRANS_Y: u32 = 2;
pub const CONTAINER_ALPHA: u32 = 3;
pub const CLIP_START: u32 = 4;
pub const CLIP_END: u32 = 5;
pub const CLIP_TOP: u32 = 6;
pub const CLIP_BOTTOM: u32 = 7;
pub const CONTAINER_BG_COLOR: u32 = 8;
pub const BIG_ALPHA: u32 = 9;
pub const BIG_SCALE: u32 = 10;
pub const BIG_X: u32 = 11;
pub const BIG_TRANS_X: u32 = 12;
pub const BIG_TRANS_Y: u32 = 13;
pub const BIG_BLUR: u32 = 14;
pub const BIG_AREA_LEFT: u32 = 15;
pub const BIG_AREA_RIGHT: u32 = 16;
pub const SMALL_ALPHA: u32 = 17;
pub const SMALL_SCALE: u32 = 18;
pub const SMALL_TRANS_X: u32 = 19;
pub const SMALL_TRANS_Y: u32 = 20;
pub const SMALL_BLUR: u32 = 21;
pub const EXPANDED_ALPHA: u32 = 22;
pub const EXPANDED_SCALE_X: u32 = 23;
pub const EXPANDED_SCALE_Y: u32 = 24;
pub const EXPANDED_TRANS_Y: u32 = 25;
pub const EXPANDED_TRANS_X: u32 = 26;
pub const EXPANDED_BLUR: u32 = 27;

pub const INIT_TO_BIG: u32 = 0;
pub const BIG_TO_EXPANDED: u32 = 1;
pub const EXPANDED_TO_BIG: u32 = 2;
pub const BIG_TO_SMALL: u32 = 3;
pub const SMALL_TO_BIG: u32 = 4;
pub const INIT_TO_SMALL: u32 = 5;
pub const BIG_TO_DELETED: u32 = 6;
pub const EXPANDED_TO_DELETED: u32 = 7;
pub const BIG_TO_TEMP_HIDDEN: u32 = 8;
pub const SMALL_TO_EXPANDED: u32 = 9;
pub const SMALL_TO_TEMP_HIDDEN: u32 = 10;
pub const INIT_TO_EXPANDED: u32 = 11;
pub const EXPANDED_TO_HIDDEN: u32 = 12;
pub const SMALL_TO_HIDDEN: u32 = 13;
pub const HIDDEN_TO_SMALL: u32 = 14;

/// Phone branch configuration. Properties absent from an explicit AnimConfig
/// use AnimConfig.sDefEase = SHOW(.95,.35), whose response is unscaled.
/// `preset_scale` must therefore be 1.0 for `explicit == false`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ConfiguredChannel {
    pub config: ChannelConfig,
    pub explicit: bool,
}

pub fn channel_config(transition: u32, channel: u32,
    container_will_be_transparent: bool) -> Option<ConfiguredChannel> {
    if channel > EXPANDED_BLUR || transition > HIDDEN_TO_SMALL { return None; }
    let mut result = ConfiguredChannel {
        config: ChannelConfig { preset: SHOW,delay_ms: 0,linear_duration_ms: 0 },
        explicit: false,
    };
    let mut set = |preset,delay_ms,linear_duration_ms| {
        result = ConfiguredChannel { config: ChannelConfig { preset,delay_ms,linear_duration_ms },explicit:true };
    };
    match transition {
        INIT_TO_BIG => match channel {
            CONTAINER_ALPHA => set(SHOW,0,1),
            CLIP_START|CLIP_END|BIG_SCALE|BIG_AREA_LEFT|BIG_AREA_RIGHT => set(APPEAR,0,0),
            BIG_ALPHA => set(HIDDEN,100,0),
            _ => {},
        },
        BIG_TO_EXPANDED => match channel {
            CONTAINER_TRANS_Y|CLIP_START|CLIP_END|CLIP_TOP|CLIP_BOTTOM|EXPANDED_SCALE_X
                |EXPANDED_SCALE_Y|BIG_SCALE|EXPANDED_TRANS_Y|BIG_TRANS_Y => set(CHANGE,0,0),
            EXPANDED_ALPHA|EXPANDED_BLUR => set(SHOW,50,0),
            BIG_ALPHA|BIG_BLUR => set(HIDDEN,0,0),
            _ => {},
        },
        EXPANDED_TO_BIG => match channel {
            CONTAINER_TRANS_Y|CLIP_START|CLIP_END|CLIP_TOP|CLIP_BOTTOM|EXPANDED_SCALE_X
                |EXPANDED_SCALE_Y|BIG_SCALE|EXPANDED_TRANS_Y|BIG_TRANS_Y => set(CHANGE,0,0),
            EXPANDED_ALPHA|EXPANDED_BLUR => set(HIDDEN,0,0),
            BIG_ALPHA|BIG_BLUR => set(SHOW,50,0),
            _ => {},
        },
        BIG_TO_SMALL|SMALL_TO_BIG => match channel {
            CLIP_START|CLIP_END|BIG_AREA_LEFT|BIG_AREA_RIGHT|CONTAINER_X|SMALL_TRANS_X => set(CHANGE,0,0),
            _ => {},
        },
        INIT_TO_SMALL => match channel {
            CONTAINER_ALPHA => set(SHOW,0,1),
            CONTAINER_X|CLIP_START|CLIP_END|SMALL_SCALE => set(APPEAR,0,0),
            SMALL_ALPHA => set(HIDDEN,100,0),
            _ => {},
        },
        BIG_TO_DELETED|BIG_TO_TEMP_HIDDEN|SMALL_TO_TEMP_HIDDEN => {
            let geometry = if transition == BIG_TO_TEMP_HIDDEN || transition == SMALL_TO_TEMP_HIDDEN {HIDDEN} else {SHOW};
            match channel {
                CONTAINER_ALPHA => if container_will_be_transparent { set(geometry,100,0) }
                    else { set(SHOW,400,100) },
                CLIP_START|CLIP_END|BIG_SCALE|BIG_AREA_LEFT|BIG_AREA_RIGHT => set(geometry,0,0),
                CONTAINER_X|SMALL_SCALE if transition==SMALL_TO_TEMP_HIDDEN => set(geometry,0,0),
                SMALL_ALPHA|SMALL_BLUR if transition==SMALL_TO_TEMP_HIDDEN => set(HIDDEN,0,0),
                BIG_ALPHA|BIG_BLUR if transition!=SMALL_TO_TEMP_HIDDEN => set(ALPHA,0,0),
                _ => {},
            }
        },
        EXPANDED_TO_DELETED => match channel {
            CONTAINER_ALPHA => if container_will_be_transparent {set(SHOW,100,0)} else {set(SHOW,400,100)},
            CONTAINER_TRANS_Y|CLIP_START|CLIP_END|CLIP_TOP|CLIP_BOTTOM|EXPANDED_TRANS_Y
                |EXPANDED_SCALE_X|EXPANDED_SCALE_Y|EXPANDED_ALPHA|EXPANDED_BLUR => set(SHOW,0,0),
            _ => {},
        },
        SMALL_TO_EXPANDED => match channel {
            CONTAINER_TRANS_Y|CONTAINER_X|CLIP_START|CLIP_END|CLIP_BOTTOM
                |EXPANDED_SCALE_X|EXPANDED_SCALE_Y|EXPANDED_TRANS_Y|SMALL_TRANS_X|SMALL_TRANS_Y => set(CHANGE,0,0),
            EXPANDED_ALPHA => set(SHOW,50,0),
            SMALL_ALPHA => set(HIDDEN,0,0),
            _ => {},
        },
        INIT_TO_EXPANDED => if channel==CONTAINER_ALPHA {set(SHOW,0,1)},
        EXPANDED_TO_HIDDEN => match channel {
            CLIP_START|CLIP_END|CLIP_TOP|CLIP_BOTTOM|CONTAINER_X|CONTAINER_TRANS_Y|SMALL_SCALE => set(CHANGE,0,0),
            CONTAINER_ALPHA => set(HIDDEN,150,0),
            _ => {},
        },
        SMALL_TO_HIDDEN => match channel {
            CONTAINER_ALPHA|CLIP_START|CLIP_END|CLIP_TOP|CLIP_BOTTOM|SMALL_SCALE => set(HIDDEN,0,0),
            _ => {},
        },
        HIDDEN_TO_SMALL => match channel {
            CONTAINER_ALPHA => set(SHOW,0,1),
            CONTAINER_X|CLIP_START|CLIP_END|CLIP_TOP|CLIP_BOTTOM|SMALL_SCALE => set(APPEAR,0,0),
            _ => {},
        },
        _ => {},
    }
    Some(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn original_plugin_art_spring_vectors() {
        let fixture=include_str!("../../tests/fixtures/motion/spring-vectors.csv");
        let mut count=0;
        for line in fixture.lines().skip(1) {
            let values: Vec<f64> = line.split(',').map(|s|s.parse().unwrap()).collect();
            let spring=Spring::new(values[0],values[1],values[2]).unwrap();
            let actual=spring.step(values[3],values[4],values[5],values[6]);
            for (actual,expected) in [(actual.value,values[7]),(actual.velocity,values[8])] {
                let tolerance=1e-10_f64.max(expected.abs()*1e-12);
                assert!((actual-expected).abs()<=tolerance,
                    "case{}: {} != {}, tolerance={}",count,actual,expected,tolerance);
            }
            count+=1;
        }
        assert_eq!(count,1200);
    }

    #[test]
    fn invalid_inputs_are_rejected() {
        assert!(Spring::new(-0.1,0.3,1.0).is_none());
        assert!(Spring::new(0.8,0.0,1.0).is_none());
        assert!(Spring::new(0.8,0.3,0.0).is_none());
        assert!(Spring::new(f64::NAN,0.3,1.0).is_none());
        assert!(preset(50,1.0).is_none());
        assert!(preset(SHOW,f32::INFINITY).is_none());
    }
}
