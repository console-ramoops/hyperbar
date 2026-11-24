//! Independent source-derived reference for HyperOS classic material blends.
//! Production rendering evaluates the unchanged native shader on the GPU; this
//! CPU implementation supplies color/recipe validation and reusable Rust data.

pub const ORDINARY_COLORS:[u32;3]=[0xff7a7a7a,0xff338000,0xb30d0d0d];
pub const ORDINARY_MODES:[u32;3]=[100,106,3];
pub const PROMOTED_COLORS:[u32;3]=[0xff999999,0xffccff00,0x99ffffff];
pub const PROMOTED_MODES:[u32;3]=[107,106,3];

fn srgb_to_linear(c:f64)->f64 {if c>0.04045 {((c+0.055)/1.055).powf(2.4)} else {c/12.92}}
fn linear_to_srgb(c:f64)->f64 {if c>0.0031308 {1.055*c.powf(1.0/2.4)-0.055} else {12.92*c}}
fn xyz_f(c:f64)->f64 {if c>0.008856 {c.powf(1.0/3.0)} else {7.787*c+16.0/116.0}}
fn xyz_inverse(c:f64)->f64 {let cube=c.powf(3.0);if cube>0.008856 {cube} else {(c-16.0/116.0)/7.787}}
fn rgb_to_lab(c:[f64;3])->[f64;3] {
    let [r,g,b]=c.map(srgb_to_linear).map(|x|x*100.0);
    let x=xyz_f((r*0.4124+g*0.3576+b*0.1805)/95.047);
    let y=xyz_f((r*0.2126+g*0.7152+b*0.0722)/100.0);
    let z=xyz_f((r*0.0193+g*0.1192+b*0.9505)/108.883);
    [116.0*y-16.0,500.0*(x-y),200.0*(y-z)]
}
fn lab_to_rgb(c:[f64;3])->[f64;3] {
    let y=(c[0]+16.0)/116.0;
    let x=xyz_inverse(c[1]/500.0+y)*95.047/100.0;
    let z=xyz_inverse(y-c[2]/200.0)*108.883/100.0;
    let y=xyz_inverse(y)*100.0/100.0;
    [x*3.2406+y*(-1.5372)+z*(-0.4986),x*(-0.9689)+y*1.8758+z*0.0415,
        x*0.0557+y*(-0.2040)+z*1.0570].map(linear_to_srgb).map(|x|x.clamp(0.0,1.0))
}
fn linear_light(base:f64,blend:f64)->f64 {
    if blend<0.5 {(base+2.0*blend-1.0).max(0.0)} else {(base+2.0*(blend-0.5)).min(1.0)}
}
fn decode(c:u32)->[f64;4] {[(c>>16&255) as f64/255.0,(c>>8&255) as f64/255.0,
    (c&255) as f64/255.0,(c>>24) as f64/255.0]}
fn mix(a:[f64;3],b:[f64;3],alpha:f64)->[f64;3] {
    std::array::from_fn(|i|b[i]*alpha+a[i]*(1.0-alpha))
}

pub fn reference_rgb(input_argb:u32,promoted:bool)->[f64;3] {
    let input=decode(input_argb);let mut background=[input[0],input[1],input[2]];
    let colors=if promoted {PROMOTED_COLORS} else {ORDINARY_COLORS};
    let modes=if promoted {PROMOTED_MODES} else {ORDINARY_MODES};
    for stage in 0..3 {
        let color=decode(colors[stage]);let src=[color[0],color[1],color[2]];
        background=match modes[stage] {
            100=>mix(background,std::array::from_fn(|i|linear_light(background[i],src[i])),color[3]),
            106=>{
                let mut lab=rgb_to_lab(background);lab[0]=(src[1]-src[0])*lab[0]+src[0]*100.0;
                mix(background,lab_to_rgb(lab),color[3])
            },
            107=>{
                let a=rgb_to_lab(src);let b=rgb_to_lab(background);
                let a=[a[0]/100.0,(a[1]+128.0)/255.0,(a[2]+128.0)/255.0];
                let b=[b[0]/100.0,(b[1]+128.0)/255.0,(b[2]+128.0)/255.0];
                let result:[f64;3]=std::array::from_fn(|i|linear_light(b[i],a[i]));
                let lab=[result[0]*100.0,result[1]*255.0-128.0,result[2]*255.0-128.0];
                mix(background,lab_to_rgb(lab),color[3])
            },
            3=>mix(background,src,color[3]),
            _=>unreachable!(),
        };
    }
    background
}

pub fn reference_argb(input_argb:u32,promoted:bool)->u32 {
    let [r,g,b]=reference_rgb(input_argb,promoted).map(|x|(x.clamp(0.0,1.0)*255.0).round() as u32);
    0xff000000|(r<<16)|(g<<8)|b
}
