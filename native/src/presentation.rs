//! Keyed ordinary-phone Big -> Small -> Hidden state chain. Zero is the
//! original Empty sentinel, not a notification. Based on SwipeEventCoordinator
//! and the three state handlers; app/miniwindows/temp-show remain separate.
use std::collections::HashSet;

#[derive(Clone,Debug,Default,PartialEq,Eq)]
pub struct Queue {pub big:i64,pub small:i64,pub hidden:Vec<i64>,sources:Vec<i64>,preferred:i64,initialized:bool}
impl Queue {
    /// Read-only destination for a gesture preview. Never commits ownership.
    pub fn preview(&self,direction:i32)->Result<Self,&'static str>{
        let mut target=self.clone();
        match direction{-1=>target.left(),1=>target.right(),_=>return Err("Invalid queue preview direction")};
        target.validate()?;Ok(target)
    }
    fn empty(&self)->bool{self.hidden.contains(&0)}
    fn ensure_empty_tail(&mut self){if !self.empty(){self.hidden.push(0);}}
    fn push_hidden(&mut self,id:i64){if id==0{return;}self.hidden.retain(|&x|x!=id);self.hidden.insert(0,id);}
    fn small_fill(&mut self)->i64{
        if self.small!=0{let previous=self.small;self.small=0;return previous;}
        let head=self.hidden.first().copied().unwrap_or(0);
        if head!=0{self.hidden.remove(0);self.small=head;}head
    }
    fn big_fill(&mut self){
        let candidate=self.small_fill();self.big=candidate;
        if candidate==0{if self.hidden.first()==Some(&0){self.hidden.remove(0);}}
        else{self.small_fill();}
    }
    fn replace_big(&mut self,id:i64){
        if id==0||self.big==id{return;}
        self.hidden.retain(|&x|x!=id);let old_big=self.big;
        if self.small==id{self.small=0;}
        self.big=id;
        if old_big!=0{let old_small=self.small;self.small=old_big;self.push_hidden(old_small);}
        if !self.empty(){self.hidden.push(0);}
    }
    pub fn sync(&mut self,sources:&[i64],preferred:i64)->Result<(),&'static str>{
        let unique:HashSet<i64>=sources.iter().copied().collect();
        if sources.iter().any(|&id|id<=0)||unique.len()!=sources.len()||preferred!=0&&!unique.contains(&preferred){return Err("Invalid presentation source identity");}
        if !self.initialized||self.sources.is_empty(){
            self.big=sources.first().copied().unwrap_or(0);self.small=sources.get(1).copied().unwrap_or(0);
            self.hidden=sources.iter().skip(2).copied().collect();if !sources.is_empty(){self.hidden.push(0);}self.initialized=true;
        }else{
            let big_removed=self.big!=0&&!unique.contains(&self.big);let small_removed=self.small!=0&&!unique.contains(&self.small);
            if big_removed{self.big=0;}if small_removed{self.small=0;}
            self.hidden.retain(|&id|id==0||unique.contains(&id));
            if big_removed{self.big_fill();}else if small_removed{self.small_fill();}
            let old:HashSet<i64>=self.sources.iter().copied().collect();
            // Snapshot order supplies arrival/priority ranking. Existing manual
            // ordering survives metadata-only snapshots.
            for (rank,&id) in sources.iter().enumerate().rev(){if old.contains(&id){continue;}
                let big_rank=sources.iter().position(|&x|x==self.big).unwrap_or(usize::MAX);
                let small_rank=sources.iter().position(|&x|x==self.small).unwrap_or(usize::MAX);
                if rank<big_rank{self.replace_big(id);}
                else if rank<small_rank{let previous=self.small;self.small=id;self.push_hidden(previous);if !self.empty(){self.hidden.push(0);}}
                else{self.push_hidden(id);if !self.empty(){self.hidden.push(0);}}
            }
        }
        self.sources=sources.to_vec();self.preferred=preferred;
        if preferred!=0{self.replace_big(preferred);}
        if sources.is_empty(){self.big=0;self.small=0;self.hidden.clear();}
        self.validate()
    }
    pub fn left(&mut self){
        if self.big==self.preferred&&self.preferred!=0{return;}
        if self.big==0{self.ensure_empty_tail();self.big_fill();return;}
        if self.small==0&&self.hidden.first().is_some_and(|&x|x!=0){self.small_fill();return;}
        self.ensure_empty_tail();let previous=self.big;self.hidden.retain(|&x|x!=previous);self.hidden.push(previous);self.big_fill();
    }
    pub fn right(&mut self){
        if self.preferred!=0{return;}
        let Some(&last)=self.hidden.last()else{return;};
        if last==0{
            if self.small!=0{let small=self.small;let big=self.big;self.push_hidden(small);self.push_hidden(big);self.big=0;self.small=0;
                if self.hidden.last()==Some(&0){self.hidden.pop();}}
            else if self.big!=0{let big=self.big;self.push_hidden(big);self.big=0;self.hidden.pop();}
        }else{
            if !self.empty(){self.hidden.insert(0,0);}self.hidden.retain(|&id|id!=last);self.replace_big(last);
        }
    }
    pub fn hide(&mut self,id:i64){
        if id==0||id==self.preferred{return;}
        if id==self.big{self.ensure_empty_tail();self.hidden.retain(|&x|x!=id);self.hidden.push(id);self.big_fill();}
        else if id==self.small{self.small=0;self.ensure_empty_tail();self.hidden.retain(|&x|x!=id);self.hidden.push(id);}
    }
    pub fn focus(&mut self,id:i64){if self.preferred==0&&self.sources.contains(&id){self.replace_big(id);}}
    pub fn validate(&self)->Result<(),&'static str>{
        let mut all=Vec::new();if self.big!=0{all.push(self.big);}if self.small!=0{all.push(self.small);}all.extend(self.hidden.iter().copied().filter(|&x|x!=0));
        let unique:HashSet<i64>=all.iter().copied().collect();let source:HashSet<i64>=self.sources.iter().copied().collect();
        if unique.len()!=all.len()||unique!=source||self.hidden.iter().filter(|&&x|x==0).count()>1{return Err("Presentation identity conservation failed");}Ok(())
    }
}

#[cfg(test)]
mod tests {
 use super::*;
 #[test] fn preview_is_read_only_and_matches_later_commit(){
  let mut q=Queue::default();q.sync(&[1,2,3],0).unwrap();let saved=q.clone();
  for direction in [-1,1]{let preview=q.preview(direction).unwrap();assert_eq!(q,saved);
   let mut committed=q.clone();if direction<0{committed.left();}else{committed.right();}assert_eq!(preview,committed);}
  assert!(q.preview(0).is_err());assert_eq!(q,saved);
  q.sync(&[1,2,3],1).unwrap();assert_eq!(q.preview(-1).unwrap(),q);assert_eq!(q.preview(1).unwrap(),q);
 }
 #[test] fn original_one_two_three_item_swipe_states(){
  let mut q=Queue::default();q.sync(&[1],0).unwrap();q.left();assert_eq!((q.big,q.small,q.hidden.clone()),(0,0,vec![1]));q.right();assert_eq!((q.big,q.small,q.hidden.clone()),(1,0,vec![0]));
  q=Queue::default();q.sync(&[1,2],0).unwrap();q.left();assert_eq!((q.big,q.small,q.hidden.clone()),(2,0,vec![0,1]));q.right();assert_eq!((q.big,q.small,q.hidden.clone()),(1,2,vec![0]));
  q.right();assert_eq!((q.big,q.small,q.hidden.clone()),(0,0,vec![1,2]));q.left();assert_eq!((q.big,q.small,q.hidden.clone()),(1,0,vec![2,0]));q.left();assert_eq!((q.big,q.small,q.hidden.clone()),(1,2,vec![0]));
  q=Queue::default();q.sync(&[1,2,3],0).unwrap();q.left();assert_eq!((q.big,q.small,q.hidden.clone()),(2,3,vec![0,1]));q.right();assert_eq!((q.big,q.small,q.hidden.clone()),(1,2,vec![3,0]));
 }
 #[test] fn snapshots_swipes_and_removal_conserve_keys(){
  for size in 1..=16{let ids:Vec<i64>=(1..=size).collect();let mut q=Queue::default();q.sync(&ids,0).unwrap();
   for step in 0..300{if step%3==0{q.right();}else{q.left();}q.validate().unwrap();let before=q.clone();q.sync(&ids,0).unwrap();assert_eq!(q,before);}
   for id in ids.iter().rev(){let remaining:Vec<i64>=q.sources.iter().copied().filter(|x|x!=id).collect();q.sync(&remaining,0).unwrap();q.validate().unwrap();}
  }
 }
 #[test] fn new_sources_and_preferred_call_survive(){
  let mut q=Queue::default();q.sync(&[1,2],0).unwrap();q.right();q.sync(&[3,1,2],0).unwrap();assert_eq!(q.big,3);q.validate().unwrap();
  q.sync(&[4,3,1,2],4).unwrap();let original=q.clone();q.left();q.right();q.hide(4);assert_eq!(q,original);q.sync(&[3,1,2],0).unwrap();q.validate().unwrap();
 }
 #[test] fn batched_sources_follow_snapshot_priority(){
  let mut q=Queue::default();q.sync(&[],0).unwrap();q.sync(&[1,2,3,4],0).unwrap();assert_eq!((q.big,q.small,q.hidden.clone()),(1,2,vec![3,4,0]));
  q=Queue::default();q.sync(&[1,2],0).unwrap();q.sync(&[4,3,1,2],0).unwrap();assert_eq!((q.big,q.small,q.hidden.clone()),(4,3,vec![1,2,0]));q.validate().unwrap();
 }
}
