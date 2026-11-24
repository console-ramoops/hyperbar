//! Native owner for the ordinary HyperOS phone transitions.
//!
//! Factory states, spring arithmetic, property thresholds and callback clock
//! are donor-verified individually. This owner composes those finite policies;
//! it does not claim equivalence to the whole Xiaomi event/state/WM system.

use crate::clock::Clock;
use crate::motion::{self, ChannelConfig, ConfiguredChannel};
use crate::states::{self, Geometry, State};

pub const MIN_VISIBLE: [f32;28]=[0.004,1.0,1.0,0.004,1.0,1.0,1.0,1.0,0.002,
    0.004,0.004,0.004,0.004,1.0,0.004,1.0,1.0,0.004,0.004,1.0,1.0,0.004,
    0.004,0.004,0.004,1.0,0.004,0.004];
const MAX_RUNNING_NS:u64=10_000_000_000;
const INIT_VELOCITY_LIMIT:f64=16.66666603088379;

#[derive(Clone, Copy, Debug)]
struct Property {
    active:bool,
    started:bool,
    just_start:bool,
    frame_count:u32,
    start_raw_ns:u64,
    linear_start:f64,
    config:ConfiguredChannel,
}
impl Default for Property {
    fn default()->Self {Self {active:false,started:false,just_start:true,frame_count:0,
        start_raw_ns:0,linear_start:0.0,config:ConfiguredChannel {
            config:ChannelConfig {preset:motion::SHOW,delay_ms:0,linear_duration_ms:0},explicit:false}}}
}

#[derive(Clone, Debug)]
pub struct Snapshot {
    pub values:State,
    pub velocities:State,
    pub targets:State,
    pub active:bool,
    pub active_mask:u32,
    pub generation:u64,
    pub transition:u32,
    pub synthetic_time_ns:u64,
}

#[derive(Clone, Debug)]
pub struct Animator {
    pub values:State,
    pub velocities:State,
    pub targets:State,
    properties:[Property;28],
    clock:Clock,
    synthetic_time_ns:u64,
    scale:f32,
    generation:u64,
    transition_id:u32,
    start_immediately:bool,
    callback_count:u64,
}

impl Default for Animator {fn default()->Self {Self::new()}}
impl Animator {
    pub fn new()->Self {Self {values:[0.0;28],velocities:[0.0;28],targets:[0.0;28],
        properties:[Property::default();28],clock:Clock::new(),synthetic_time_ns:0,
        scale:1.0,generation:0,transition_id:0,start_immediately:true,callback_count:0}}

    pub fn active(&self)->bool {self.properties.iter().any(|p|p.active)}
    pub fn snapshot(&self)->Snapshot {
        let mask=self.properties.iter().enumerate().fold(0_u32,|m,(i,p)|m|if p.active {1<<i} else {0});
        Snapshot {values:self.values,velocities:self.velocities,targets:self.targets,
            active:mask!=0,active_mask:mask,generation:self.generation,
            transition:self.transition_id,synthetic_time_ns:self.synthetic_time_ns}
    }

    pub fn snap(&mut self,kind:u32,geometry:&Geometry,background:i32)->Result<(),&'static str> {
        let state=states::factory(kind,geometry,background).ok_or("Invalid HyperOS factory state")?;
        self.values=state;self.targets=state;self.velocities=[0.0;28];
        self.properties=[Property::default();28];self.clock.restart();
        self.synthetic_time_ns=0;self.callback_count=0;
        self.generation=self.generation.wrapping_add(1);
        Ok(())
    }

    pub fn transition(&mut self,id:u32,geometry:&Geometry,background:i32,
        scale:f32,transparent:bool)->Result<(),&'static str> {
        if !scale.is_finite() || scale<=0.0 {return Err("Invalid HyperOS animation scale");}
        let (from,to)=states::transition_factories(id).ok_or("Unknown HyperOS phone transition")?;
        let target=states::factory(to,geometry,background).ok_or("Invalid HyperOS target geometry")?;
        let mut configurations=[Property::default();28];
        for (i,p) in configurations.iter_mut().enumerate() {
            p.config=motion::channel_config(id,i as u32,transparent).ok_or("Unknown HyperOS property")?;
            motion::preset(p.config.config.preset,if p.config.explicit {scale} else {1.0})
                .ok_or("Invalid HyperOS property spring")?;
        }
        let was_active=self.active();
        if !was_active {self.clock.restart();}
        if let Some(kind)=from {
            self.values=states::factory(kind,geometry,background).ok_or("Invalid HyperOS initial geometry")?;
            self.velocities=[0.0;28];
        }
        if !states::validate_interruption(&self.values,&self.velocities) {return Err("Nonfinite HyperOS current state");}
        self.targets=target;self.scale=scale;self.transition_id=id;
        self.synthetic_time_ns=0;self.callback_count=0;self.start_immediately=true;
        self.generation=self.generation.wrapping_add(1);
        let now=self.clock.raw_total_ns();
        for (i,p) in configurations.iter_mut().enumerate() {
            p.active=self.values[i]!=target[i] || self.velocities[i].abs()>=INIT_VELOCITY_LIMIT;
            p.started=p.config.config.delay_ms==0;
            p.start_raw_ns=now;p.linear_start=self.values[i];
        }
        // The ordinary source states retain the same typed background color.
        // If a caller changes that color, the host's material system owns its
        // interpolation; treating an ARGB integer as a scalar spring is invalid.
        self.values[8]=target[8];self.velocities[8]=0.0;configurations[8].active=false;
        self.properties=configurations;
        Ok(())
    }

    /// Restore externally observed property values/velocities before retargeting.
    pub fn swipe_expanded(&mut self,geometry:&Geometry,dx:f32,dy:f32)->Result<bool,&'static str>{
        if !dx.is_finite()||!dy.is_finite(){return Err("Nonfinite swipe displacement");}
        let Some((target,mask))=crate::swipe::expanded(geometry,self.values[8] as i32,dx,dy) else{return Ok(false);};
        self.gesture_targets(target,mask)?;Ok(true)
    }
    pub fn reset_swipe(&mut self,geometry:&Geometry)->Result<(),&'static str>{
        let target=states::factory(states::EXPANDED,geometry,self.values[8] as i32).ok_or("Invalid swipe reset geometry")?;
        self.gesture_targets(target,(1<<28)-1)
    }
    pub fn swipe_capsule(&mut self,geometry:&Geometry,transition:u32,dx:f32)->Result<(),&'static str>{
        let (target,mask)=crate::swipe::capsule(geometry,self.values[8] as i32,transition,dx)
            .ok_or("Invalid capsule preview target")?;
        self.gesture_targets(target,mask)
    }
    pub fn reset_capsule(&mut self,geometry:&Geometry,kind:u32)->Result<(),&'static str>{
        if !matches!(kind,states::BIG|states::SMALL|states::CUTOUT){return Err("Invalid capsule reset kind");}
        let target=states::factory(kind,geometry,self.values[8] as i32).ok_or("Invalid capsule reset geometry")?;
        self.gesture_targets(target,(1<<28)-1)
    }
    fn gesture_targets(&mut self,target:State,mask:u32)->Result<(),&'static str>{
        if !states::validate_interruption(&self.values,&self.velocities)||target.iter().any(|v|!v.is_finite()){return Err("Nonfinite gesture state");}
        if !self.active(){self.clock.restart();}
        self.synthetic_time_ns=0;self.callback_count=0;self.start_immediately=true;self.generation=self.generation.wrapping_add(1);
        let now=self.clock.raw_total_ns();
        for (i,value) in target.iter().enumerate(){if mask&(1<<i)==0||i==8{continue;}
            self.targets[i]=*value;self.properties[i]=Property{active:self.values[i]!=*value||self.velocities[i].abs()>=INIT_VELOCITY_LIMIT,
                started:true,just_start:true,frame_count:0,start_raw_ns:now,linear_start:self.values[i],
                config:ConfiguredChannel{config:ChannelConfig{preset:motion::SHOW,delay_ms:0,linear_duration_ms:0},explicit:false}};
        }
        Ok(())
    }

    /// Restore externally observed property values/velocities before retargeting.
    pub fn press(&mut self,kind:u32,geometry:&Geometry,background:i32,scale:f32,pressed:bool)->Result<(),&'static str>{
        if !scale.is_finite()||scale<=0.0{return Err("Invalid gesture animation scale");}
        let (target,mask)=if pressed {states::press(kind,geometry,background).ok_or("Invalid press surface")?}
            else {if !matches!(kind,states::BIG|states::EXPANDED|states::SMALL){return Err("Invalid release surface");}
                (states::factory(kind,geometry,background).ok_or("Invalid release geometry")?,(1_u32<<28)-1)};
        if !states::validate_interruption(&self.values,&self.velocities){return Err("Nonfinite press state");}
        if !self.active(){self.clock.restart();}
        self.scale=scale;self.synthetic_time_ns=0;self.callback_count=0;self.start_immediately=true;
        self.generation=self.generation.wrapping_add(1);
        let now=self.clock.raw_total_ns();
        let scale_mask=match kind {states::BIG|states::SMALL=>1_u32<<10,states::EXPANDED=>(1_u32<<23)|(1_u32<<24),_=>0};
        for (i,value) in target.iter().enumerate(){
            if mask&(1<<i)==0||i==8{continue;}
            let explicit=(4..=7).contains(&i)||scale_mask&(1<<i)!=0;
            let preset=if pressed&&explicit{motion::SCALE}else{motion::SHOW};
            self.targets[i]=*value;
            self.properties[i]=Property{active:self.values[i]!=*value||self.velocities[i].abs()>=INIT_VELOCITY_LIMIT,
                started:true,just_start:true,frame_count:0,start_raw_ns:now,linear_start:self.values[i],
                config:ConfiguredChannel{config:ChannelConfig{preset,delay_ms:0,linear_duration_ms:0},explicit}};
        }
        Ok(())
    }

    /// Restore externally observed property values/velocities before retargeting.
    pub fn interrupt(&mut self,values:State,velocities:State)->Result<(),&'static str> {
        if !states::validate_interruption(&values,&velocities) {return Err("Nonfinite HyperOS interruption");}
        self.values=values;self.velocities=velocities;Ok(())
    }
    /// Cancellation retains the last visible state and velocity.
    pub fn cancel(&mut self) {for p in &mut self.properties {p.active=false;}self.clock.restart();}
    /// Forced completion snaps values to the target and preserves measured speed.
    pub fn finish(&mut self) {self.values=self.targets;for p in &mut self.properties {p.active=false;}self.clock.restart();}

    pub fn frame(&mut self,callback_delta_ns:u64,predicted_period_ns:u64,
        ratio:f32)->Result<Snapshot,&'static str> {
        if !self.active() {return Ok(self.snapshot());}
        let time=self.clock.frame(callback_delta_ns,predicted_period_ns,ratio)?;
        if self.start_immediately || self.callback_count>0 {
            self.synthetic_time_ns=self.synthetic_time_ns.saturating_add(time.period_ns);
        }
        self.callback_count=self.callback_count.saturating_add(1);
        for i in 0..28 {
            let p=&mut self.properties[i];
            if !p.active {continue;}
            let config=p.config.config;
            if !p.started {
                if self.synthetic_time_ns<(config.delay_ms as u64)*1_000_000 {continue;}
                p.started=true;p.start_raw_ns=time.raw_total_ns.saturating_sub(time.raw_delta_ns);
                p.linear_start=self.values[i];p.just_start=true;p.frame_count=0;
                if self.values[i]==self.targets[i] && self.velocities[i].abs()<INIT_VELOCITY_LIMIT {
                    p.active=false;continue;
                }
            }
            let elapsed_ns=time.raw_total_ns.saturating_sub(p.start_raw_ns);
            let dt=if p.just_start && self.velocities[i]==0.0 {0.0} else {
                p.frame_count=p.frame_count.saturating_add(1);time.dt_seconds
            };
            if config.linear_duration_ms>0 {
                let elapsed_ms=elapsed_ns/1_000_000;
                let duration=config.linear_duration_ms as u64;
                if elapsed_ms>=duration {self.values[i]=self.targets[i];p.active=false;}
                else {
                    let fraction=elapsed_ms as f32/duration as f32;
                    let from=p.linear_start as f32;let to=self.targets[i] as f32;
                    self.values[i]=(from+fraction*(to-from)) as f64;
                }
            } else {
                let spring=motion::preset(config.preset,if p.config.explicit {self.scale} else {1.0})
                    .ok_or("Invalid HyperOS active spring")?;
                let result=spring.folme_frame(self.values[i],self.targets[i],self.velocities[i],dt,p.frame_count==1);
                if !result.value.is_finite() || !result.velocity.is_finite() {return Err("HyperOS spring became nonfinite");}
                self.values[i]=result.value;self.velocities[i]=result.velocity;
                if motion::at_equilibrium(result.value,self.targets[i],result.velocity,MIN_VISIBLE[i])
                    || elapsed_ns>MAX_RUNNING_NS {
                    self.values[i]=self.targets[i];p.active=false;
                }
            }
            p.just_start=false;
        }
        Ok(self.snapshot())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn original_folme_runner_retarged_sequence_vectors() {
        let data=include_str!("../../tests/fixtures/motion/update-sequence-vectors.csv");
        let mut case=usize::MAX;let mut owner=Animator::new();let mut slot=9;let mut rows=0;
        for row in data.lines().skip(1) {
            let v:Vec<f64>=row.split(',').map(|s|s.parse().unwrap()).collect();
            let id=v[0] as usize;
            let preset=v[1] as u32;
            let config=ConfiguredChannel {config:ChannelConfig {preset,delay_ms:0,linear_duration_ms:0},explicit:true};
            if id!=case {
                case=id;owner=Animator::new();slot=if v[2] as f32==1.0 {1} else {9};
                owner.values[slot]=v[3];owner.targets[slot]=v[4];owner.velocities[slot]=v[5];
                owner.properties[slot]=Property {active:v[3]!=v[4] || v[5].abs()>=INIT_VELOCITY_LIMIT,
                    started:true,just_start:true,frame_count:0,start_raw_ns:0,linear_start:v[3],config};
            }
            if v[7]==1.0 {
                let was_active=owner.active();
                if !was_active {owner.clock.restart();}
                owner.targets[slot]=v[8];
                owner.properties[slot]=Property {active:owner.values[slot]!=v[8]
                    || owner.velocities[slot].abs()>=INIT_VELOCITY_LIMIT,started:true,just_start:true,
                    frame_count:0,start_raw_ns:owner.clock.raw_total_ns(),linear_start:owner.values[slot],config};
            }
            let frame=owner.frame(16_666_666,16_666_666,1.0).unwrap();
            assert!((frame.values[slot]-v[9]).abs()<=1e-10_f64.max(v[9].abs()*1e-12),
                "case{},frame{} position {} != {}",case,v[6],frame.values[slot],v[9]);
            assert!((frame.velocities[slot]-v[10]).abs()<=1e-10_f64.max(v[10].abs()*1e-12),
                "case{},frame{} speed {} != {}",case,v[6],frame.velocities[slot],v[10]);
            assert_eq!(frame.active,v[11]==1.0,"case{},frame{} active",case,v[6]);
            rows+=1;
        }
        assert_eq!(rows,4800);
    }
    fn geometry()->Geometry {Geometry::from_slice(&[1081.0,85.0,23.0,501.0,475.0,277.0,
        941.0,969.0,231.0,135.0,70.0,85.0,837.0,33.0,39.5,67.0,151.0,183.0,
        2.625,0.0,0.0]).unwrap()}

    #[test]
    fn transitions_complete_and_interruption_keeps_motion() {
        let g=geometry();let mut owner=Animator::new();
        owner.transition(0,&g,-16777216,1.0,false).unwrap();
        for _ in 0..9 {owner.frame(8_333_333,8_333_333,1.0).unwrap();}
        let visible=owner.values;let velocity=owner.velocities;
        owner.transition(1,&g,-16777216,1.0,false).unwrap();
        assert_eq!(owner.values,visible);assert_eq!(owner.velocities,velocity);
        for _ in 0..300 {owner.frame(8_333_333,8_333_333,1.0).unwrap();}
        assert!(!owner.active());assert_eq!(owner.values,owner.targets);
        for id in [2,3,4,1,12,0,6] {
            owner.transition(id,&g,-16777216,1.0,false).unwrap();
            for _ in 0..300 {owner.frame(8_333_333,8_333_333,1.0).unwrap();}
            assert!(!owner.active(),"transition {}",id);assert_eq!(owner.values,owner.targets);
        }
    }
    #[test]
    fn secondary_hide_and_return_retain_owner_motion(){
        let g=geometry();let mut owner=Animator::new();owner.snap(states::SMALL,&g,-16777216).unwrap();
        owner.swipe_capsule(&g,crate::swipe::SMALL_TO_HIDDEN,130.0).unwrap();
        for _ in 0..8{owner.frame(8_333_333,8_333_333,1.0).unwrap();}
        let values=owner.values;let velocities=owner.velocities;
        owner.transition(motion::SMALL_TO_HIDDEN,&g,-16777216,0.75,false).unwrap();
        assert_eq!(owner.values,values);assert_eq!(owner.velocities,velocities);
        for _ in 0..300{owner.frame(8_333_333,8_333_333,1.0).unwrap();}
        assert_eq!(owner.values,states::factory(states::HIDDEN,&g,-16777216).unwrap());
        let hidden=owner.values;
        owner.transition(motion::HIDDEN_TO_SMALL,&g,-16777216,0.75,false).unwrap();
        assert_eq!(owner.values,hidden);assert_eq!(owner.properties[3].config.config.linear_duration_ms,1);
        assert_eq!(owner.properties[6].config.config.preset,motion::APPEAR);
        assert!(!owner.properties[17].config.explicit);
        for _ in 0..300{owner.frame(8_333_333,8_333_333,1.0).unwrap();}
        assert_eq!(owner.values,states::factory(states::SMALL,&g,-16777216).unwrap());assert!(!owner.active());
    }

    #[test]
    fn custom_thresholds_match_original_constructors() {
        let data=include_str!("../../tests/fixtures/motion/threshold-vectors.csv");
        for row in data.lines().skip(1) {
            let cols:Vec<&str>=row.split(',').collect();let index:usize=cols[0].parse().unwrap();
            let original=cols[2].parse::<f32>().unwrap();
            assert_eq!(MIN_VISIBLE[index],if original==-1.0 {0.002} else {original});
        }
    }
    #[test]
    fn press_targets_use_original_factory_coordinates(){
        let data=include_str!("../../tests/fixtures/motion/state-vectors.csv");let mut cases=0;
        for row in data.lines().skip(1){
            let v:Vec<f64>=row.split(',').map(|s|s.parse().unwrap()).collect();let kind=v[0] as u32;
            if !matches!(kind,states::BIG|states::EXPANDED|states::SMALL){continue;}
            let g=Geometry::from_slice(&v[1..22]).unwrap();let original:&[f64]=&v[22..50];
            let (target,mask)=states::press(kind,&g,-16777216).unwrap();
            let (w,h,inset,scale_slots)=match kind {states::BIG=>(g.big_view_width,g.island_height,0.025_f32,vec![10]),
                states::EXPANDED=>(g.expanded_width,g.expanded_height,0.025_f32,vec![23,24]),
                _=>(g.small_width,g.island_height,0.05_f32,vec![18])};
            let mut expected=original.to_vec();expected[4]=(original[4] as f32+w as f32*inset) as f64;
            expected[5]=(original[5] as f32-w as f32*inset) as f64;expected[6]=(original[6] as f32+h as f32*inset) as f64;
            expected[7]=(original[7] as f32-h as f32*inset) as f64;
            for slot in scale_slots {expected[slot]=if kind==states::SMALL{0.9_f32 as f64}else{0.95_f32 as f64};}
            for slot in 0..28{assert_eq!(target[slot],expected[slot],"kind{} slot{}",kind,slot);}
            assert_eq!(mask.count_ones(),if kind==states::EXPANDED{8}else{7});cases+=1;
        }
        assert_eq!(cases,192);
    }
    #[test]
    fn press_release_and_small_owner_keep_source_policy(){
        let g=geometry();
        for kind in [states::BIG,states::EXPANDED,states::SMALL]{
            let mut owner=Animator::new();owner.snap(kind,&g,-16777216).unwrap();let base=owner.values;
            owner.press(kind,&g,-16777216,0.83,true).unwrap();assert_eq!(owner.values,base);
            if kind==states::SMALL{assert!(!owner.properties[18].config.explicit);assert_eq!(owner.properties[18].config.config.preset,motion::SHOW);}
            for _ in 0..600{owner.frame(8_333_333,8_333_333,1.0).unwrap();}
            assert!(!owner.active());let pressed=owner.values;let velocities=owner.velocities;
            owner.press(kind,&g,-16777216,0.83,false).unwrap();assert_eq!(owner.values,pressed);assert_eq!(owner.velocities,velocities);
            for _ in 0..600{owner.frame(8_333_333,8_333_333,1.0).unwrap();}
            assert!(!owner.active());assert_eq!(owner.values,base);
            let before=owner.snapshot();assert!(owner.press(kind,&g,-16777216,f32::NAN,true).is_err());assert_eq!(owner.values,before.values);assert_eq!(owner.targets,before.targets);
        }
    }
    #[test]
    fn swipe_reset_preserves_source_and_returns_to_expanded(){
        let g=geometry();let mut owner=Animator::new();owner.snap(states::EXPANDED,&g,-16777216).unwrap();let original=owner.values;
        for (dx,dy) in [(180.9,0.0),(-180.9,0.0),(0.0,-90.8)]{
            assert!(owner.swipe_expanded(&g,dx,dy).unwrap());assert!(owner.targets[22]<1.0&&owner.targets[27]>0.0);
            for _ in 0..200{owner.frame(8_333_333,8_333_333,1.0).unwrap();}
            assert!(!owner.active());owner.reset_swipe(&g).unwrap();
            for _ in 0..200{owner.frame(8_333_333,8_333_333,1.0).unwrap();}
            assert_eq!(owner.values,original);assert!(!owner.active());
        }
        assert!(!owner.swipe_expanded(&g,0.0,50.0).unwrap());assert_eq!(owner.values,original);
        assert!(owner.swipe_expanded(&g,f32::NAN,0.0).is_err());assert_eq!(owner.values,original);
    }
}
