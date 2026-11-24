//! FolmeEngine callback clock, retaining the shipped five-delta averaging and
//! API33 predicted-vsync override. No dropped-frame catch-up substeps.

pub const MAX_DELTA_NS: u64 = 16_666_666;

#[derive(Clone, Copy, Debug)]
pub struct FrameTime {
    pub raw_delta_ns: u64,
    pub period_ns: u64,
    pub dt_seconds: f64,
    pub raw_total_ns: u64,
}

#[derive(Clone, Debug)]
pub struct Clock {
    history: [u64; 5],
    position: usize,
    first: bool,
    raw_total_ns: u64,
}

impl Default for Clock {fn default()->Self {Self::new()}}
impl Clock {
    pub fn new()->Self {Self {history:[0;5],position:0,first:true,raw_total_ns:0}}

    /// Engine inactivity resets last timestamp but retains the averaging ring.
    pub fn restart(&mut self) {self.first=true;self.raw_total_ns=0;}
    pub fn raw_total_ns(&self)->u64 {self.raw_total_ns}

    pub fn frame(&mut self,callback_delta_ns: u64,predicted_period_ns: u64,
        ratio: f32)->Result<FrameTime,&'static str> {
        if !ratio.is_finite() || ratio<=0.0 || predicted_period_ns>1_000_000_000 {
            return Err("Invalid Folme callback ratio or predicted period");
        }
        let delta=if self.first {0} else {callback_delta_ns};
        let mut history=self.history;
        history[self.position]=delta;
        let count=history.iter().filter(|&&x|x>0).count() as u64;
        let total=history.iter().fold(0_u64,|a,&b|a.saturating_add(b));
        let mut average=if count==0 {0} else {total/count};
        if average==0 || average>MAX_DELTA_NS {average=MAX_DELTA_NS;}
        // Original Java: long-to-float, divide by Float ratio, float-to-double,
        // Math.ceil, double-to-long. The f32 rounding is intentional.
        let computed=(average as f32/ratio) as f64;
        let period=if predicted_period_ns>0 {predicted_period_ns}
            else {computed.ceil() as u64};
        if period==0 || period>1_000_000_000 {return Err("Invalid computed Folme period");}
        self.first=false;self.history=history;self.position=(self.position+1)%5;
        self.raw_total_ns=self.raw_total_ns.saturating_add(delta);
        Ok(FrameTime {raw_delta_ns:delta,period_ns:period,
            dt_seconds:period as f64/1_000_000_000.0,raw_total_ns:self.raw_total_ns})
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn original_android_engine_clock_vectors() {
        let vectors=include_str!("../../tests/fixtures/motion/clock-vectors.csv");
        let mut clock=Clock::new();
        let mut last_ratio=-1.0_f32;
        let mut last_timestamp=0_u64;
        let mut cases=0;
        for row in vectors.lines().skip(1) {
            let columns:Vec<&str>=row.split(',').collect();
            let ratio=columns[0].parse::<f32>().unwrap();
            let timestamp=columns[1].parse::<u64>().unwrap();
            if ratio!=last_ratio {clock=Clock::new();last_timestamp=timestamp;last_ratio=ratio;}
            let actual=clock.frame(timestamp-last_timestamp,0,ratio).unwrap();
            assert_eq!(actual.raw_delta_ns,columns[2].parse::<u64>().unwrap());
            assert_eq!(actual.period_ns,columns[3].parse::<u64>().unwrap());
            last_timestamp=timestamp;
            cases+=1;
        }
        assert_eq!(cases,36);
    }

    #[test]
    fn predicted_vsync_directly_overrides_average_and_ratio() {
        let mut clock=Clock::new();
        assert_eq!(clock.frame(0,8_333_333,0.5).unwrap().period_ns,8_333_333);
        assert_eq!(clock.frame(100_000_000,33_333_333,2.0).unwrap().period_ns,33_333_333);
    }
}
