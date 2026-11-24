//! Per-view ShaderDriver clock used by the original incoming-call WaveShader.
#[derive(Clone,Debug,Default)]
pub struct Wave {start:i64,last:i64,running:bool}
impl Wave {
    pub fn frame(&mut self,now:i64,event:i32)->Result<f32,&'static str>{
        if now<0{return Err("Invalid wave clock");}
        if !(0..=2).contains(&event){return Err("Invalid wave event");}
        if event==0&&self.running&&now<self.last{return Err("Wave clock moved backwards");}
        if event==1{self.start=now;self.running=true;}
        if event==2{self.running=false;}
        self.last=now;
        Ok(if self.running{(now-self.start) as f32/1000.0_f32}else{0.0})
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn original_integer_elapsed_then_float_seconds_and_restart(){
        let mut wave=Wave::default();assert_eq!(wave.frame(1000,1),Ok(0.0));
        for elapsed in [1,33,1234,16_777_217,100_000_003]{
            assert_eq!(wave.frame(1000+elapsed,0).unwrap().to_bits(),(elapsed as f32/1000.0_f32).to_bits());
        }
        assert_eq!(wave.frame(101_000_003,2),Ok(0.0));
        assert_eq!(wave.frame(101_004_000,0),Ok(0.0));
        assert_eq!(wave.frame(101_004_000,1),Ok(0.0));
        assert_eq!(wave.frame(101_005_000,0),Ok(1.0));
    }
    #[test]
    fn invalid_events_leave_running_clock_unchanged(){
        let mut wave=Wave::default();wave.frame(5000,1).unwrap();wave.frame(5100,0).unwrap();
        assert!(wave.frame(0,3).is_err());assert!(wave.frame(-1,2).is_err());assert!(wave.frame(5099,0).is_err());
        assert_eq!(wave.frame(5200,0),Ok(0.2));
    }
}
