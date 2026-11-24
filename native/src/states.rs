//! Parametric phone states from the original HyperOS IslandAnimStateFactory.
//! Dimensions are integer pixels because the Android getters return int. All
//! intermediate geometry arithmetic retains the donor's float32/int order.

pub type State = [f64; 28];

pub const CUTOUT: u32 = 0;
pub const BIG: u32 = 1;
pub const EXPANDED: u32 = 2;
pub const SMALL: u32 = 3;
pub const HIDDEN: u32 = 4;
pub const BIG_HIDDEN: u32 = 5;

#[derive(Clone, Copy, Debug)]
pub struct Geometry {
    pub screen_width: i32,
    pub island_height: i32,
    pub island_y: i32,
    pub big_view_width: i32,
    pub current_big_width: i32,
    pub big_x: i32,
    pub expanded_width: i32,
    pub expanded_max_width: i32,
    pub expanded_height: i32,
    pub expanded_y: i32,
    pub expanded_margin: i32,
    pub small_width: i32,
    /// Absolute x supplied by DynamicIslandAnimationController.getSmallIslandX.
    pub small_x: i32,
    pub cutout_height: i32,
    pub cutout_y: f32,
    pub fixed_cutout_width: i32,
    pub left_area_width: i32,
    pub right_area_width: i32,
    pub density: f32,
    pub notch: bool,
    pub rtl: bool,
    /// Nominal width of the current BIG owner, independent of this receiver.
    pub swipe_big_width: i32,
}

impl Geometry {
    /// 21 factory metrics plus an optional current BIG owner's nominal width.
    pub fn from_slice(metrics: &[f64]) -> Option<Self> {
        if !matches!(metrics.len(),21|22) || metrics.iter().any(|x|!x.is_finite()) {return None;}
        for (i,v) in metrics.iter().enumerate() {
            if !matches!(i,14|18) && (*v < i32::MIN as f64 || *v > i32::MAX as f64 || v.fract()!=0.0) {
                return None;
            }
        }
        let g=Self {
            screen_width: metrics[0] as i32,island_height: metrics[1] as i32,
            island_y: metrics[2] as i32,big_view_width: metrics[3] as i32,
            current_big_width: metrics[4] as i32,big_x: metrics[5] as i32,
            expanded_width: metrics[6] as i32,expanded_max_width: metrics[7] as i32,
            expanded_height: metrics[8] as i32,expanded_y: metrics[9] as i32,
            expanded_margin: metrics[10] as i32,small_width: metrics[11] as i32,
            small_x: metrics[12] as i32,cutout_height: metrics[13] as i32,
            cutout_y: metrics[14] as f32,fixed_cutout_width: metrics[15] as i32,
            left_area_width: metrics[16] as i32,right_area_width: metrics[17] as i32,
            density: metrics[18] as f32,notch: metrics[19]!=0.0,rtl: metrics[20]!=0.0,
            swipe_big_width: metrics.get(21).copied().unwrap_or(metrics[4]) as i32,
        };
        if g.screen_width<=0 || g.island_height<=0 || g.big_view_width<0
            || g.current_big_width<0 || g.swipe_big_width<0 || g.expanded_width<0 || g.expanded_max_width<0
            || g.expanded_height<0 || g.small_width<0 || g.cutout_height<0
            || g.fixed_cutout_width<0 || g.left_area_width<0 || g.right_area_width<0
            || g.density<=0.0 || !g.density.is_finite() || !g.cutout_y.is_finite() {
            return None;
        }
        Some(g)
    }

    fn safe_big_width(self) -> f32 {if self.big_view_width==0 {1.0} else {self.big_view_width as f32}}
    fn effective_expanded_width(self) -> f32 {
        if self.expanded_width>1 {self.expanded_width as f32}
        else if self.expanded_max_width>1 {self.expanded_max_width as f32}
        else {0.0}
    }
    fn expanded_scale_x(self,width: i32) -> f32 {
        let expanded=self.effective_expanded_width();
        if expanded<=1.0 || width<=0 {1.0} else {width as f32/expanded}
    }
    fn expanded_scale_y(self,height: f32) -> f32 {
        if self.expanded_height<=1 || height<=0.0 {1.0}
        else {height/self.expanded_height as f32}
    }
    fn expanded_to_big_scale(self) -> f32 {
        let expanded=self.effective_expanded_width();
        if expanded<=1.0 || self.big_view_width<=0 {1.0}
        else {expanded/self.big_view_width as f32}
    }
    fn big_start(self) -> f32 {self.big_x as f32}
    fn big_end(self) -> f32 {
        self.big_start()+((self.current_big_width as f32/self.safe_big_width())*self.big_view_width as f32)
    }
    fn top(self) -> f32 {self.island_y as f32}
    fn bottom(self) -> f32 {self.top()+self.island_height as f32}
    fn cutout_start(self) -> f32 {
        self.screen_width as f32/2.0 - if self.notch {(self.cutout_height/2) as f32}
            else {(self.fixed_cutout_width/2) as f32}
    }
    fn cutout_end(self) -> f32 {
        let width=if self.notch {self.cutout_height} else {self.fixed_cutout_width};
        self.cutout_start()+((width as f32/self.safe_big_width())*self.big_view_width as f32)
    }
    fn cutout_top(self) -> f32 {
        if self.notch {0.0} else {self.cutout_y-(self.cutout_height/2) as f32}
    }
    fn cutout_bottom(self) -> f32 {
        self.cutout_top()+((self.cutout_height as f32/self.island_height as f32)*self.island_height as f32)
    }
    fn small_start(self) -> f32 {self.screen_width as f32/2.0-(self.small_width/2) as f32}
    fn small_end(self) -> f32 {
        self.small_start()+((self.small_width as f32/self.safe_big_width())*self.big_view_width as f32)
    }
    fn small_translation_x(self) -> f32 {
        (self.small_width/2).wrapping_add(self.small_x.wrapping_sub(self.screen_width/2)) as f32
    }
    fn area_left_collapse(self) -> f32 {
        if self.rtl {-(self.left_area_width as f32)} else {self.left_area_width as f32}
    }
    fn area_right_collapse(self) -> f32 {
        if self.rtl {self.right_area_width as f32} else {-(self.right_area_width as f32)}
    }
    fn small_to_big_x(self) -> f32 {
        let dp8=(8.0_f32*self.density) as i32;
        if self.rtl {self.left_area_width as f32-dp8 as f32}
        else {-(self.left_area_width as f32)+dp8 as f32}
    }
    fn small_to_expanded_x(self) -> f32 {
        let difference=self.expanded_width.wrapping_sub(self.small_width);
        (if self.rtl {difference} else {difference.wrapping_neg()}) as f32/2.0
    }
    fn expanded_to_island_y(self) -> f32 {
        if self.expanded_height<=0 {return 0.0;}
        let expanded=self.expanded_height as f32;
        (expanded-((self.island_height as f32/expanded)*expanded))/2.0
    }
    fn content_to_expanded_y(self) -> f32 {
        self.expanded_height.wrapping_sub(self.island_height) as f32/2.0
    }
}

fn promote(values: [f32;28],background: i32) -> State {
    let mut output=values.map(|x|x as f64);
    output[8]=background as f64;
    output
}

/// Source factory states. Use the current ColorDrawable color, or black when
/// the container background is another Drawable, for `background`.
pub fn factory(kind: u32,g: &Geometry,background: i32) -> Option<State> {
    let g=*g;
    let top=g.top();
    let bottom=g.bottom();
    let result=match kind {
        CUTOUT => {
            let cutout_top=g.cutout_top();
            let cutout_bottom=g.cutout_bottom();
            let scale_y=g.expanded_scale_y((cutout_bottom-cutout_top).max(0.0));
            let expanded=g.expanded_height as f32;
            let translation_y=((expanded-expanded*scale_y)/2.0)+(g.island_y as f32-cutout_top);
            [0.0,0.0,0.0,0.0,g.cutout_start(),g.cutout_end(),cutout_top,cutout_bottom,0.0,
             0.0,1.0,0.0,0.0,0.0,1.0,g.area_left_collapse(),g.area_right_collapse(),
             0.0,g.fixed_cutout_width as f32/g.island_height as f32,0.0,0.0,1.0,
             0.0,g.expanded_scale_x(g.fixed_cutout_width),scale_y,-translation_y,0.0,1.0]
        },
        BIG => [0.0,0.0,0.0,1.0,g.big_start(),g.big_end(),top,bottom,0.0,
            1.0,1.0,0.0,0.0,0.0,0.0,0.0,0.0,
            0.0,1.0,g.small_to_big_x(),0.0,0.0,
            0.0,g.expanded_scale_x(g.big_view_width),g.expanded_scale_y(g.island_height as f32),
            -g.expanded_to_island_y(),0.0,1.0],
        EXPANDED => [0.0,0.0,g.expanded_y.wrapping_sub(g.island_y) as f32,1.0,
            g.expanded_margin as f32,g.expanded_margin as f32+g.expanded_width as f32,
            top,top+g.expanded_height as f32,0.0,
            0.0,g.expanded_to_big_scale(),0.0,0.0,g.content_to_expanded_y(),1.0,0.0,0.0,
            0.0,1.0,g.small_to_expanded_x(),g.content_to_expanded_y(),0.0,
            1.0,1.0,1.0,0.0,0.0,0.0],
        SMALL|HIDDEN => {
            let mut values=[0.0,g.small_translation_x(),0.0,1.0,g.small_start(),g.small_end(),top,bottom,0.0,
                0.0,1.0,0.0,0.0,0.0,0.0,g.area_left_collapse(),g.area_right_collapse(),
                1.0,1.0,0.0,0.0,0.0,
                0.0,g.expanded_scale_x(g.small_width),g.expanded_scale_y(g.island_height as f32),
                -g.expanded_to_island_y(),0.0,1.0];
            if kind==HIDDEN {
                values[3]=0.0;
                values[4]=g.small_width as f32*0.2+g.small_start();
                values[5]=g.small_end()-g.small_width as f32*0.2;
                values[6]=g.island_height as f32*0.2+top;
                values[7]=bottom-g.island_height as f32*0.2;
                values[18]=0.6;
            }
            values
        },
        BIG_HIDDEN => {
            let mut result=factory(BIG,&g,background)?;
            result[3]=0.0;
            result[4]=(g.big_view_width as f32*0.1+g.big_start()) as f64;
            result[5]=(g.big_end()-g.big_view_width as f32*0.1) as f64;
            result[6]=(g.island_height as f32*0.1+top) as f64;
            result[7]=(bottom-g.island_height as f32*0.1) as f64;
            result[9]=0.0;
            result[10]=0.8_f32 as f64;
            result[14]=1.0;
            return Some(result);
        },
        _ => return None,
    };
    let output=promote(result,background);
    if output.iter().any(|x|!x.is_finite()) {None} else {Some(output)}
}

/// Source/target factory IDs for the ordinary phone transition IDs in motion.rs.
/// IslandGestureAnimator press state. Multiplication/addition retain the
/// original float32 order; unlisted properties are not part of the press state.
pub fn press(kind:u32,g:&Geometry,background:i32)->Option<(State,u32)>{
    let (width,height,fraction,scale_slots)=match kind {
        BIG=>(g.big_view_width,g.island_height,0.025_f32,1_u32<<10),
        EXPANDED=>(g.expanded_width,g.expanded_height,0.025_f32,(1_u32<<23)|(1_u32<<24)),
        SMALL=>(g.small_width,g.island_height,0.05_f32,1_u32<<18),
        _=>return None,
    };
    let mut target=factory(kind,g,background)?;
    let dx=width as f32*fraction;let dy=height as f32*fraction;
    target[4]=(target[4] as f32+dx) as f64;target[5]=(target[5] as f32-dx) as f64;
    target[6]=(target[6] as f32+dy) as f64;target[7]=(target[7] as f32-dy) as f64;
    for (i,value) in target.iter_mut().enumerate(){if scale_slots&(1<<i)!=0{*value=if kind==SMALL{0.9_f32 as f64}else{0.95_f32 as f64};}}
    Some((target,(1<<1)|(1<<2)|(1<<4)|(1<<5)|(1<<6)|(1<<7)|scale_slots))
}

/// Source/target factory IDs for the ordinary phone transition IDs in motion.rs.
/// `from` is an explicit reset only for initial appearance. Other transitions
/// should retain their current values and velocities, including interruption.
pub fn transition_factories(transition: u32) -> Option<(Option<u32>,u32)> {
    Some(match transition {
        0 => (Some(CUTOUT),BIG),
        1 => (None,EXPANDED),
        2 => (None,BIG),
        3 => (None,SMALL),
        4 => (None,BIG),
        5 => (Some(CUTOUT),SMALL),
        6|7|8|10 => (None,CUTOUT),
        9 => (None,EXPANDED),
        11 => (Some(CUTOUT),EXPANDED),
        12 => (None,HIDDEN),
        13 => (None,HIDDEN),
        14 => (None,SMALL),
        _ => return None,
    })
}

/// Fullscreen/freeform interrupted geometry is passed by the WindowAnimController
/// as existing property values plus velocities. Reusing these rather than
/// resetting to a factory state preserves the original continuity contract.
pub fn validate_interruption(values: &State,velocities: &State) -> bool {
    values.iter().chain(velocities.iter()).all(|v|v.is_finite())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn original_donor_factory_bytecode_art_vectors() {
        let data=include_str!("../../tests/fixtures/motion/state-vectors.csv");
        let mut rows=0;
        for line in data.lines().skip(1) {
            let v: Vec<f64>=line.split(',').map(|s|s.parse().unwrap()).collect();
            assert_eq!(v.len(),50);
            let geometry=Geometry::from_slice(&v[1..22]).unwrap();
            let actual=factory(v[0] as u32,&geometry,-16777216).unwrap();
            for slot in 0..28 {
                assert_eq!(actual[slot],v[22+slot],"row{},slot{}",rows,slot);
            }
            rows+=1;
        }
        assert_eq!(rows,384);
    }
    #[test]
    fn finite_states_and_odd_pixel_integer_rounding() {
        let metrics=[1081.0,85.0,23.0,501.0,475.0,277.0,941.0,969.0,231.0,135.0,70.0,
            85.0,837.0,33.0,39.5,67.0,151.0,183.0,2.625,0.0,0.0];
        let g=Geometry::from_slice(&metrics).unwrap();
        let cutout=factory(CUTOUT,&g,-16777216).unwrap();
        assert_eq!(cutout[4],507.5); // screen/2f - fixedCutout/2 int
        assert_eq!(cutout[6],23.5); // cutoutY - cutoutHeight/2 int
        let small=factory(SMALL,&g,-16777216).unwrap();
        assert_eq!(small[1],339.0); // smallWidth/2 + smallX - screen/2, int first
        assert_eq!(small[4],498.5); // screen/2f - smallWidth/2 int
        for kind in CUTOUT..=BIG_HIDDEN {
            assert!(factory(kind,&g,-16777216).unwrap().iter().all(|x|x.is_finite()));
        }
    }
}
