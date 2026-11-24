//! MusicBgShader.BgShader/CircleColor/PauseInterpolator, sysui widget 1.0.5.
//! The moving three-color field itself is the unchanged music_panel_bg.agsl.

pub const FRAME_SIZE: usize = 5;
pub const NONE: i32 = 0;
pub const START: i32 = 1;
pub const PAUSE: i32 = 2;
pub const RESUME: i32 = 3;
pub const STOP: i32 = 4;
pub const COLOR: i32 = 5;

#[derive(Default)]
pub struct Background {
    started: bool,
    start: i64,
    paused: Option<i64>,
    pause_duration: i64,
    color_start: i64,
    color_pause_duration: i64,
    color_paused: Option<i64>,
    previous: [i32; 3],
    current: [i32; 3],
    target: u32,
    packet: [f32; FRAME_SIZE],
}

fn progress(now: i64, start: i64, pauses: i64) -> f32 {
    ((now.saturating_sub(start).saturating_sub(pauses)) as f32 / 600.0_f32)
        .clamp(0.0, 1.0)
}

impl Background {
    fn sample(&mut self, now: i64) {
        let time = ((now as f64 - self.start as f64 - self.pause_duration as f64)
            / 1000.0_f64) as f32;
        let blend = progress(now, self.color_start, self.color_pause_duration);
        for (i, shift) in [16, 8, 0].into_iter().enumerate() {
            let next = ((self.target >> shift) & 255) as i32;
            // CircleColor interpolates integer RGB channels, not ARGB alpha.
            self.current[i] = (((next - self.previous[i]) as f32 * blend)
                + self.previous[i] as f32) as i32;
            self.packet[i + 1] = self.current[i] as f32 / 255.0_f32;
        }
        self.packet[0] = time.max(0.0);
        self.packet[4] = ((self.target >> 24) as f32 / 255.0_f32)
            * progress(now, self.start, self.pause_duration);
    }

    pub fn frame(&mut self, now: i64, event: i32, color: u32,
        output: &mut [f32; FRAME_SIZE]) -> bool {
        let now = now.max(0);
        match event {
            START => {
                self.started = true;
                self.start = now;
                self.paused = None;
                self.pause_duration = 0;
                self.previous = [0; 3];
                self.current = [0; 3];
                self.color_start = now;
                self.color_pause_duration = 0;
                self.color_paused = None;
                self.packet = [0.0; FRAME_SIZE];
            }
            COLOR => {
                if self.started && self.paused.is_none() { self.sample(now); }
                self.previous = self.current;
                self.target = color;
                self.color_start = now;
                self.color_pause_duration = 0;
                self.color_paused = self.paused.map(|_| now);
            }
            PAUSE => {
                if self.started && self.paused.is_none() {
                    self.sample(now);
                    self.paused = Some(now);
                    self.color_paused = Some(now);
                }
            }
            RESUME => {
                if let Some(stop) = self.paused.take() {
                    self.pause_duration += now.saturating_sub(stop);
                    if let Some(stop) = self.color_paused.take() {
                        self.color_pause_duration += now.saturating_sub(stop);
                    }
                }
            }
            STOP => {
                let target = self.target;
                *self = Self::default();
                self.target = target;
            }
            NONE => {},
            _ => { *output = [0.0; FRAME_SIZE]; return false; }
        }
        if self.started && self.paused.is_none() { self.sample(now); }
        *output = self.packet;
        self.started && self.paused.is_none()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn donor_integer_rgb_and_six_hundred_ms_fade() {
        let mut bg=Background::default();let mut p=[0.0;5];
        bg.frame(1000,COLOR,0xff804020,&mut p);bg.frame(1000,START,0,&mut p);
        bg.frame(1150,NONE,0,&mut p);
        assert_eq!(p,[0.15,32.0/255.0,16.0/255.0,8.0/255.0,0.25]);
        bg.frame(1600,NONE,0,&mut p);assert_eq!(p[4],1.0);
        assert_eq!(p[1],128.0/255.0);
    }
    #[test]
    fn playback_pause_preserves_phase_and_rgb() {
        let mut bg=Background::default();let mut p=[0.0;5];
        bg.frame(1000,COLOR,0xffff8000,&mut p);bg.frame(1000,START,0,&mut p);
        assert!(!bg.frame(1250,PAUSE,0,&mut p));let frozen=p;
        assert!(!bg.frame(9250,NONE,0,&mut p));assert_eq!(p,frozen);
        assert!(bg.frame(9250,RESUME,0,&mut p));assert_eq!(p,frozen);
        bg.frame(9350,NONE,0,&mut p);assert_eq!(p[0],0.35);
    }
    #[test]
    fn color_changes_retarget_current_rgb_and_snap_alpha() {
        let mut bg=Background::default();let mut p=[0.0;5];
        bg.frame(0,COLOR,0xffff0000,&mut p);bg.frame(0,START,0,&mut p);
        bg.frame(600,NONE,0,&mut p);bg.frame(600,COLOR,0x800000ff,&mut p);
        assert_eq!(p[1],1.0);assert_eq!(p[4],128.0/255.0);
        bg.frame(900,NONE,0,&mut p);
        assert_eq!(p[1],127.0/255.0);assert_eq!(p[3],127.0/255.0);
    }
    #[test]
    fn stopped_driver_has_no_frames_and_restarts_from_black() {
        let mut bg=Background::default();let mut p=[0.0;5];
        bg.frame(0,COLOR,0xff336699,&mut p);bg.frame(0,START,0,&mut p);
        bg.frame(1000,NONE,0,&mut p);assert!(!bg.frame(1000,STOP,0,&mut p));
        assert_eq!(p,[0.0;5]);assert!(bg.frame(9000,START,0,&mut p));
        assert_eq!(p,[0.0;5]);
    }
}
