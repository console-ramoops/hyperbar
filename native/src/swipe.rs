//! Original phone IslandSwipeAnimator expanded-to-big target arithmetic.
use crate::states::{self,Geometry,State};

pub fn ratio(extent:i32,displacement:f32)->f32{
    if extent==0 {0.0}else{((displacement as i32) as f32/extent as f32).abs()*0.14_f32}
}
pub fn alpha(ratio:f32)->f32{((ratio*(-0.8_f32))/0.14_f32+1.0_f32).max(0.2_f32)}

pub const BIG_TO_HIDDEN:u32=0;
pub const BIG_TO_SMALL:u32=1;
pub const SMALL_TO_BIG:u32=2;
pub const SMALL_TO_HIDDEN:u32=3;
pub const HIDDEN_TO_BIG:u32=4;
pub const SMALL_TO_TEMP_HIDDEN:u32=5;

/// IslandSwipeAnimator's ordinary-phone preview targets. Queue ownership does
/// not change until release; callers animate each current owner's own surface.
pub fn capsule(g:&Geometry,background:i32,transition:u32,dx:f32)->Option<(State,u32)>{
    if !dx.is_finite(){return None;}
    let amount=ratio(g.screen_width/2,dx);
    let fade=alpha(amount);
    let kind=match transition{
        BIG_TO_HIDDEN|BIG_TO_SMALL=>states::BIG,
        SMALL_TO_BIG|SMALL_TO_HIDDEN|SMALL_TO_TEMP_HIDDEN=>states::SMALL,
        HIDDEN_TO_BIG=>states::CUTOUT,
        _=>return None,
    };
    let mut target=states::factory(kind,g,background)?;
    let mut mask=(1<<3)|(1<<4)|(1<<5)|(1<<6)|(1<<7);
    match transition{
        BIG_TO_HIDDEN|BIG_TO_SMALL=>{
            let inset=(g.big_view_width as f32*amount) as f64*0.5_f64;
            target[4]+=inset;target[5]-=inset;
            target[1]=if transition==BIG_TO_SMALL{
                let small_x=if g.rtl {target_small_translation(g)} else {g.small_x as f32};
                ((small_x-g.big_x as f32)*amount) as f64
            }else{0.0};
            target[2]=0.0;target[3]=1.0;target[9]=fade as f64;
            target[15]=((if g.rtl{-g.big_view_width}else{g.big_view_width}) as f32*amount) as f64*0.5_f64;
            target[16]=((if g.rtl{g.big_view_width}else{-g.big_view_width}) as f32*amount) as f64*0.5_f64;
            mask|=(1<<1)|(1<<2)|(1<<9)|(1<<15)|(1<<16);
        },
        SMALL_TO_HIDDEN=>{
            let x=(g.small_width as f32*amount) as f64*0.5_f64;
            let y=(g.island_height as f32*amount) as f64*0.5_f64;
            target[4]+=x;target[5]-=x;target[6]+=y;target[7]-=y;
            target[3]=0.8_f32 as f64;target[17]=fade as f64;target[18]=0.8_f32 as f64;
            mask|=(1<<17)|(1<<18);
        },
        SMALL_TO_BIG=>{
            let inset=(g.swipe_big_width as f32*amount) as f64*0.25_f64;
            target[4]-=inset;target[5]+=inset;
            // IslandCoordinateCalculator returns a translation relative to the
            // centered clip, whereas Geometry.small_x is an absolute position.
            target[1]=((target[1]+if g.rtl{inset}else{-inset}) as f32) as f64;
            target[2]=0.0;target[17]=0.8_f32 as f64;
            // This donor branch leaves container alpha unchanged.
            mask&=!(1<<3);mask|=(1<<1)|(1<<2)|(1<<17);
        },
        HIDDEN_TO_BIG=>{
            let x=(g.big_view_width as f32*amount) as f64*0.5_f64;
            let y=(g.island_height as f32*amount) as f64*0.5_f64;
            target[4]-=x;target[5]+=x;target[6]-=y;target[7]+=y;
            target[1]=0.0;target[2]=0.0;target[3]=1.0;target[9]=0.0;
            mask|=(1<<1)|(1<<2)|(1<<9);
        },
        SMALL_TO_TEMP_HIDDEN=>{
            let shift=(g.swipe_big_width as f32*amount) as f64*0.5_f64;
            target[1]+=if g.rtl{shift}else{-shift};
            target[3]=1.0;target[17]=fade as f64;
            mask=(1<<1)|(1<<3)|(1<<17);
        },
        _=>return None,
    }
    Some((target,mask))
}
fn target_small_translation(g:&Geometry)->f32{
    (g.small_width/2).wrapping_add(g.small_x.wrapping_sub(g.screen_width/2)) as f32
}
pub fn expanded(g:&Geometry,background:i32,dx:f32,dy:f32)->Option<(State,u32)>{
    if !dx.is_finite()||!dy.is_finite(){return None;}
    let horizontal=dx.abs()>dy.abs();
    if !horizontal&&(dy>=0.0||dx.abs()>=dy.abs()){return None;}
    let mut target=states::factory(states::EXPANDED,g,background)?;
    let amount=ratio(if horizontal{g.expanded_width}else{g.expanded_height},if horizontal{dx}else{dy}).abs();
    let fade=alpha(amount);target[22]=fade as f64;target[27]=(1.0_f32-fade) as f64;
    let mut mask=(1<<1)|(1<<2)|(1<<4)|(1<<5)|(1<<6)|(1<<7)|(1<<22)|(1<<23)|(1<<24)|(1<<27);
    if horizontal{
        let inset=(g.expanded_width as f32*amount)*0.5_f32;
        target[4]=(target[4] as f32+inset) as f64;target[5]=(target[5] as f32-inset) as f64;
        target[23]=(1.0_f32-amount.abs()) as f64;target[24]=1.0;
    }else{
        target[7]=(target[7] as f32-g.expanded_height as f32*amount) as f64;
        target[23]=1.0;target[24]=(1.0_f32-amount) as f64;
        // Original int negation -> f32 multiplication -> f64 half.
        target[25]=(-(g.expanded_height as f32)*amount) as f64*0.5_f64;mask|=1<<25;
    }
    Some((target,mask))
}

#[cfg(test)]
mod tests{
    use super::*;
    #[test]
    fn retained_float_division_and_pixel_truncation(){
        assert_eq!(ratio(100,49.9),0.49_f32*0.14_f32);
        assert_eq!(ratio(100,-49.9),ratio(100,49.9));assert_eq!(ratio(0,100.0),0.0);
        assert_eq!(alpha(0.0),1.0);assert_eq!(alpha(0.14),0.2_f32);
    }
    #[test]
    fn original_arithmetic_method_vectors(){
        let vectors=include_str!("../../tests/fixtures/motion/swipe-math-vectors.csv");let mut count=0;
        for row in vectors.lines().skip(1){let fields:Vec<&str>=row.split(',').collect();
            let extent=fields[0].parse::<i32>().unwrap();let displacement=fields[1].parse::<i32>().unwrap();
            let amount=ratio(extent,displacement as f32);
            assert_eq!(amount.to_bits(),fields[2].parse::<u32>().unwrap());assert_eq!(alpha(amount).to_bits(),fields[3].parse::<u32>().unwrap());count+=1;
        }
        assert_eq!(count,104);
    }
    fn geometry()->Geometry{
        Geometry::from_slice(&[1080.0,104.0,56.0,300.0,300.0,390.0,900.0,900.0,
            500.0,56.0,90.0,104.0,800.0,90.0,56.0,100.0,90.0,110.0,3.0,0.0,0.0]).unwrap()
    }
    #[test]
    fn capsule_preview_preserves_source_half_and_quarter_geometry(){
        let g=geometry();let r=ratio(540,70.0);let big=states::factory(states::BIG,&g,0).unwrap();
        let (target,mask)=capsule(&g,0,BIG_TO_SMALL,70.0).unwrap();
        let half=(300.0_f32*r) as f64*0.5;
        assert_eq!(target[4],big[4]+half);assert_eq!(target[5],big[5]-half);
        assert_eq!(target[1],(410.0_f32*r) as f64);assert_eq!(target[9],alpha(r) as f64);
        assert_ne!(mask&(1<<15),0);
        let small=states::factory(states::SMALL,&g,0).unwrap();
        let (incoming,_)=capsule(&g,0,SMALL_TO_BIG,-70.0).unwrap();
        let quarter=(300.0_f32*r) as f64*0.25;
        assert_eq!(incoming[4],small[4]-quarter);assert_eq!(incoming[5],small[5]+quarter);
    }
    #[test]
    fn capsule_preview_direction_pixel_truncation_and_invalid_inputs(){
        let mut g=geometry();
        assert_eq!(capsule(&g,0,BIG_TO_HIDDEN,49.9).unwrap().0,capsule(&g,0,BIG_TO_HIDDEN,-49.9).unwrap().0);
        let left=capsule(&g,0,BIG_TO_HIDDEN,90.0).unwrap().0[15];g.rtl=true;
        assert_eq!(capsule(&g,0,BIG_TO_HIDDEN,90.0).unwrap().0[15],-left);
        assert!(capsule(&g,0,BIG_TO_HIDDEN,f32::NAN).is_none());
        assert!(capsule(&g,0,99,10.0).is_none());
        g.swipe_big_width=0;
        assert_eq!(capsule(&g,0,SMALL_TO_BIG,90.0).unwrap().0[1],states::factory(states::SMALL,&g,0).unwrap()[1]);
    }
    #[test]
    fn small_preview_uses_coordinate_translation_not_absolute_position(){
        let mut g=geometry();let resting=states::factory(states::SMALL,&g,0).unwrap();
        assert_eq!(capsule(&g,0,SMALL_TO_BIG,0.0).unwrap().0[1],resting[1]);
        assert_eq!(capsule(&g,0,SMALL_TO_TEMP_HIDDEN,0.0).unwrap().0[1],resting[1]);
        let ratio=ratio(g.screen_width/2,100.0);
        let quarter=(g.current_big_width as f32*ratio) as f64*0.25;
        assert_eq!(capsule(&g,0,SMALL_TO_BIG,100.0).unwrap().0[1],((resting[1]-quarter) as f32) as f64);
        g.rtl=true;
        assert_eq!(capsule(&g,0,SMALL_TO_BIG,100.0).unwrap().0[1],((resting[1]+quarter) as f32) as f64);
    }
    #[test]
    fn paired_geometry_and_cross_owner_width_are_distinct(){
        let mut g=geometry();g.big_view_width=420;g.current_big_width=260;g.swipe_big_width=340;
        let small=states::factory(states::SMALL,&g,0).unwrap();let r=ratio(g.screen_width/2,100.0);
        let target=capsule(&g,0,SMALL_TO_BIG,100.0).unwrap().0;
        assert_eq!(target[4],small[4]-(340.0_f32*r) as f64*0.25);
        g.rtl=true;
        assert_eq!(capsule(&g,0,BIG_TO_SMALL,100.0).unwrap().0[1],((target_small_translation(&g)-g.big_x as f32)*r) as f64);
    }
}
