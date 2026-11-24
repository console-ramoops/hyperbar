//! Original flashlight beam packets and its shared one-shot Folme driver.
use crate::{clock::Clock,motion};
use serde_json::Value;
use std::collections::HashMap;

#[derive(Clone,Debug,PartialEq)]
pub struct Keyframes([[f32;36];5]);
impl Keyframes {
    pub fn parse(json:&str)->Result<Self,&'static str>{
        if json.len()>65_536{return Err("Flashlight keyframes too large");}
        let value:Value=serde_json::from_str(json).map_err(|_|"Invalid flashlight JSON")?;
        let root=value.as_object().ok_or("Missing flashlight keyframe object")?;
        if root.len()!=5{return Err("Flashlight requires five quartile keyframes");}
        let mut frames=[[0.0;36];5];
        for (i,key) in ["0%","25%","50%","75%","100%"].iter().enumerate(){
            let row=root.get(*key).and_then(Value::as_object).ok_or("Missing flashlight quartile")?;
            let mut slot=0;
            for beam in [1,5,6]{
                for (name,count) in [("point_f",2),("line_y",1),("e",1),("highband",1),("rgb",3),("alpha",1),("scale_x",1),("shift",1),("dispersion",1)]{
                    let field=row.get(&format!("beam{}_{}",beam,name)).ok_or("Missing flashlight beam field")?;
                    if count==1{
                        frames[i][slot]=number(field)?;slot+=1;
                    }else{
                        let components=field.as_array().ok_or("Flashlight beam vector is not an array")?;
                        if components.len()!=count{return Err("Wrong flashlight beam vector width");}
                        for component in components{frames[i][slot]=number(component)?;slot+=1;}
                    }
                }
            }
        }
        Ok(Self(frames))
    }
    pub fn packet(&self,progress:f32)->Result<[f32;37],&'static str>{
        if !progress.is_finite()||!(0.0..=1.0).contains(&progress){return Err("Invalid flashlight progress");}
        // The source row's `progress` field is intentionally not an abscissa.
        let q=progress*100.0_f32;let row=(q/25.0_f32).floor() as usize;
        let next=(row+1).min(4);let fraction=(q%25.0_f32)/25.0_f32;
        let mut packet=[0.0;37];packet[0]=progress;
        for i in 0..36{let from=self.0[row][i];packet[i+1]=from+(self.0[next][i]-from)*fraction;}
        Ok(packet)
    }
}
fn number(value:&Value)->Result<f32,&'static str>{
    let number=value.as_f64().ok_or("Non-numeric flashlight field")? as f32;
    if number.is_finite(){Ok(number)}else{Err("Nonfinite flashlight field")}
}

#[derive(Clone,Debug,Default)]
struct Driver {
    clock:Clock,last_ns:u64,seen_frame:bool,callbacks:u64,synthetic_ns:u64,
    value:f64,velocity:f64,started:bool,just_started:bool,spring_frames:u64,
    start_raw_ns:u64,settled:bool,
}
impl Driver {
    fn start(&mut self,now:u64){let mut clock=std::mem::take(&mut self.clock);clock.restart();*self=Self{clock,last_ns:now,..Self::default()};}
    fn frame(&mut self,now:u64,period:u64)->Result<(),&'static str>{
        if now<self.last_ns{return Err("Flashlight clock moved backwards");}
        if self.settled||self.seen_frame&&now==self.last_ns{return Ok(());}
        let time=self.clock.frame(now-self.last_ns,period,1.0)?;
        self.last_ns=now;self.seen_frame=true;
        if self.callbacks>0{self.synthetic_ns=self.synthetic_ns.saturating_add(time.period_ns);}
        self.callbacks=self.callbacks.saturating_add(1);
        if !self.started {
            if self.synthetic_ns<150_000_000{return Ok(());}
            self.started=true;self.just_started=true;self.start_raw_ns=time.raw_total_ns.saturating_sub(time.raw_delta_ns);
        }
        let dt=if self.just_started&&self.velocity==0.0{0.0}else{self.spring_frames=self.spring_frames.saturating_add(1);time.dt_seconds};
        // Literal floats from FolmeEase.spring(1.8f,.6f), promoted as the donor does.
        let spring=motion::Spring::new(1.8_f32 as f64,0.6_f32 as f64,1.0).ok_or("Invalid flashlight spring")?;
        let result=spring.folme_frame(self.value,1.0,self.velocity,dt,self.spring_frames==1);
        if !result.value.is_finite()||!result.velocity.is_finite(){return Err("Nonfinite flashlight spring");}
        self.value=result.value;self.velocity=result.velocity;self.just_started=false;
        if motion::at_equilibrium(self.value,1.0,self.velocity,0.002)
            ||time.raw_total_ns.saturating_sub(self.start_raw_ns)>10_000_000_000{
            self.value=1.0;self.settled=true;
        }
        Ok(())
    }
}

#[derive(Default)]
pub struct Registry {keyframes:Option<Keyframes>,owners:HashMap<i64,bool>,visible:usize,driver:Driver}
impl Registry {
    pub fn create(&mut self,id:i64,json:&str)->Result<(),&'static str>{
        if id<=0||self.owners.contains_key(&id){return Err("Invalid flashlight identity");}
        let keyframes=Keyframes::parse(json)?;
        if self.keyframes.as_ref().is_some_and(|current|current!=&keyframes){return Err("Conflicting shared flashlight keyframes");}
        self.keyframes=Some(keyframes);self.owners.insert(id,false);Ok(())
    }
    pub fn visible(&mut self,id:i64,visible:bool,now_ns:i64)->Result<(),&'static str>{
        if now_ns<0{return Err("Invalid flashlight time");}
        let owner=self.owners.get_mut(&id).ok_or("Flashlight owner is closed")?;
        if *owner==visible{return Ok(());}
        *owner=visible;
        if visible{if self.visible==0{self.driver.start(now_ns as u64);}self.visible+=1;}
        else{self.visible-=1;}
        Ok(())
    }
    pub fn frame(&mut self,id:i64,now_ns:i64,period_ns:i64)->Result<([f32;37],bool),&'static str>{
        if now_ns<0||period_ns<0||period_ns>1_000_000_000{return Err("Invalid flashlight frame clock");}
        let visible=*self.owners.get(&id).ok_or("Flashlight owner is closed")?;
        if visible{self.driver.frame(now_ns as u64,period_ns as u64)?;}
        let progress=if visible{(self.driver.value as f32).clamp(0.0,1.0)}else{0.0};
        let packet=self.keyframes.as_ref().ok_or("Missing flashlight keyframes")?.packet(progress)?;
        Ok((packet,visible&&!self.driver.settled))
    }
    pub fn destroy(&mut self,id:i64){
        if self.owners.remove(&id)==Some(true){self.visible-=1;}
        if self.owners.is_empty(){self.keyframes=None;self.driver=Driver::default();}
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    // Build test inputs from the measured quartile packets, without embedding
    // a vendor resource file in the source distribution.
    fn source()->String {
        let mut root=serde_json::Map::new();
        for (key,progress) in [("0%",0.0_f32),("25%",0.25),("50%",0.5),("75%",0.75),("100%",1.0)] {
            let bits=include_str!("../../tests/fixtures/motion/flashlight-interpolation-vectors.csv")
                .lines().skip(1).map(|line|line.split(',').map(|v|v.parse::<u32>().unwrap()).collect::<Vec<_>>())
                .find(|row|row[0]==progress.to_bits()).expect("Missing measured quartile");
            let mut row=serde_json::Map::new();let mut slot=1;
            // Deliberately disagree with the quartile: the parser must ignore it.
            row.insert("progress".into(),serde_json::json!(99));
            for beam in [1,5,6] {
                for (name,count) in [("point_f",2),("line_y",1),("e",1),("highband",1),("rgb",3),("alpha",1),("scale_x",1),("shift",1),("dispersion",1)] {
                    let values:Vec<f32>=bits[slot..slot+count].iter().map(|v|f32::from_bits(*v)).collect();
                    row.insert(format!("beam{}_{}",beam,name),if count==1 {serde_json::json!(values[0])} else {serde_json::json!(values)});
                    slot+=count;
                }
            }
            root.insert(key.into(),Value::Object(row));
        }
        Value::Object(root).to_string()
    }
    #[test]
    fn original_apk_art_interpolation_packets_match_bit_for_bit(){
        let frames=Keyframes::parse(&source()).unwrap();let mut rows=0;
        for line in include_str!("../../tests/fixtures/motion/flashlight-interpolation-vectors.csv").lines().skip(1){
            let bits:Vec<u32>=line.split(',').map(|value|value.parse().unwrap()).collect();assert_eq!(bits.len(),37);
            let packet=frames.packet(f32::from_bits(bits[0])).unwrap();
            for i in 0..37{assert_eq!(packet[i].to_bits(),bits[i],"row{} slot{}",rows,i);}
            rows+=1;
        }
        assert_eq!(rows,198);
    }
    #[test]
    fn quartiles_are_uniform_despite_source_progress_field(){
        let data=Keyframes::parse(&source()).unwrap();
        for (i,p) in [0.0,0.25,0.5,0.75,1.0].iter().enumerate(){assert_eq!(&data.packet(*p).unwrap()[1..],&data.0[i]);}
        let parsed:Value=serde_json::from_str(&source()).unwrap();assert_ne!(parsed["50%"]["progress"].as_f64().unwrap(),0.5);
        assert!(data.packet(f32::NAN).is_err());assert!(data.packet(-0.001).is_err());assert!(data.packet(1.001).is_err());
    }
    #[test]
    fn shared_frame_and_visibility_follow_one_shot_owner_lifecycle(){
        let mut registry=Registry::default();registry.create(1,&source()).unwrap();registry.create(2,&source()).unwrap();
        registry.visible(1,true,0).unwrap();
        assert_eq!(registry.frame(1,0,16_666_667).unwrap().0[0],0.0);
        assert_eq!(registry.frame(1,100_000_000,16_666_667).unwrap().0[0],0.0);
        let mut now=100_000_000;
        for _ in 0..20{now+=16_666_667;registry.frame(1,now,16_666_667).unwrap();}
        let first=registry.frame(1,now,16_666_667).unwrap();assert!(first.0[0]>0.0);
        registry.visible(2,true,now).unwrap();assert_eq!(registry.frame(2,now,16_666_667).unwrap(),first);
        registry.visible(1,false,now).unwrap();now+=16_666_667;
        let off=registry.frame(1,now,16_666_667).unwrap();assert_eq!(off.0[0],0.0);assert!(!off.1);
        assert!(registry.frame(2,now,16_666_667).unwrap().0[0]>=first.0[0]);
        registry.visible(2,false,now).unwrap();registry.visible(1,true,now).unwrap();
        assert_eq!(registry.frame(1,now,16_666_667).unwrap().0[0],0.0);
        let mut final_packet=([0.0;37],true);
        for _ in 0..600{now+=16_666_667;final_packet=registry.frame(1,now,16_666_667).unwrap();if !final_packet.1{break;}}
        assert!(!final_packet.1);assert_eq!(final_packet.0[0],1.0);assert_eq!(registry.visible,1);
        registry.destroy(1);registry.destroy(2);assert_eq!(registry.visible,0);assert!(registry.keyframes.is_none());
    }
    #[test]
    fn invalid_input_and_closed_owners_preserve_last_packet(){
        let mut registry=Registry::default();registry.create(1,&source()).unwrap();registry.visible(1,true,0).unwrap();
        let packet=registry.frame(1,0,16_666_667).unwrap();
        assert!(registry.frame(1,-1,16_666_667).is_err());assert!(registry.frame(1,0,1_000_000_001).is_err());
        assert!(registry.create(2,"{}").is_err());assert_eq!(registry.frame(1,0,16_666_667).unwrap(),packet);
        registry.destroy(1);assert!(registry.visible(1,true,0).is_err());assert!(registry.frame(1,0,16_666_667).is_err());
    }
}
