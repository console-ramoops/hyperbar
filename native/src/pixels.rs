use std::cell::RefCell;
type Result<T>=std::result::Result<T,String>;
#[derive(Default)]struct Workspace{image:Vec<i32>,scratch:Vec<i32>,red:Vec<i32>,green:Vec<i32>,blue:Vec<i32>,quotient:Vec<u8>,divisor:usize}
thread_local!{static WORK:RefCell<Workspace>=RefCell::new(Workspace::default());}
fn size(values:&mut Vec<i32>,length:usize)->Result<()>{if length>values.len(){values.try_reserve_exact(length-values.len()).map_err(|_|"Native blur buffer allocation failed")?;}values.resize(length,0);Ok(())}

/// Owned per-worker buffers avoid Java array pinning and native aliasing. The
/// caller's array is committed only after all six source-equivalent sweeps.
pub fn blur_with(width:usize,height:usize,radius:usize,read:impl FnOnce(&mut[i32])->Result<()>,write:impl FnOnce(&[i32])->Result<()>)->Result<()>{
    let length=width.checked_mul(height).ok_or("Invalid screenshot dimensions")?;
    if width==0 || height==0 || length>i32::MAX as usize{return Err("Invalid screenshot dimensions".into());}
    let radius=radius.clamp(1,32);
    WORK.with(|work|{
        let mut work=work.borrow_mut();
        let Workspace{image,scratch,red,green,blue,quotient,divisor}=&mut *work;
        size(image,length)?;size(scratch,length)?;size(red,width)?;size(green,width)?;size(blue,width)?;
        let current_divisor=radius*2+1;
        if *divisor!=current_divisor{
            let max_sum=255*current_divisor;
            if max_sum+1>quotient.len(){quotient.try_reserve_exact(max_sum+1-quotient.len()).map_err(|_|"Native blur quotient allocation failed")?;}
            quotient.resize(max_sum+1,0);
            for (sum,out) in quotient.iter_mut().enumerate(){*out=(sum/current_divisor) as u8;}
            *divisor=current_divisor;
        }
        read(image)?;
        for _ in 0..3{horizontal(image,scratch,width,height,radius,quotient);vertical(scratch,image,width,height,radius,red,green,blue,quotient);}
        write(image)
    })
}
#[cfg(test)]fn blur(pixels:&mut[i32],width:usize,height:usize,radius:usize)->Result<()>{
    if pixels.len()!=width.checked_mul(height).unwrap_or(0){return Err("Invalid screenshot dimensions".into());}
    let source=pixels.to_vec();blur_with(width,height,radius,|out|{out.copy_from_slice(&source);Ok(())},|out|{pixels.copy_from_slice(out);Ok(())})
}
#[inline]fn channels(color:i32)->(i32,i32,i32){let x=color as u32;(((x>>16)&255) as i32,((x>>8)&255) as i32,(x&255) as i32)}
#[cfg(test)]#[inline]fn color(red:i32,green:i32,blue:i32,divisor:i32)->i32{(0xff000000u32|((red/divisor) as u32)<<16|((green/divisor) as u32)<<8|(blue/divisor) as u32) as i32}
#[inline]fn exact_quotient_color(red:i32,green:i32,blue:i32,lookup:&[u8])->i32{
    // Each sum consists of exactly2r+1 unsigned8-bit channels, so it is in
    // 0..=255*(2r+1). The table stores the same truncating integer division;
    // no reciprocal/float approximation changes a pixel value.
    (0xff000000u32|(lookup[red as usize] as u32)<<16|(lookup[green as usize] as u32)<<8|lookup[blue as usize] as u32) as i32
}
fn horizontal(source:&[i32],out:&mut[i32],width:usize,height:usize,radius:usize,lookup:&[u8]){
    for y in 0..height{
        let base=y*width;let (mut r,mut g,mut b)=(0,0,0);
        for x in -(radius as isize)..=radius as isize{
            let pixel=base+x.clamp(0,(width-1) as isize) as usize;let (a,c,d)=channels(source[pixel]);r+=a;g+=c;b+=d;
        }
        for x in 0..width{
            out[base+x]=exact_quotient_color(r,g,b,lookup);
            let leaving=source[base+x.saturating_sub(radius)];let entering=source[base+(x+radius+1).min(width-1)];
            let (lr,lg,lb)=channels(leaving);let (er,eg,eb)=channels(entering);r+=er-lr;g+=eg-lg;b+=eb-lb;
        }
    }
}
/// The original scans each column separately. Keeping per-column sums while
/// scanning contiguous rows gives identical integer windows/divisions with
/// fewer cache misses; it does not alter the blur/tint or introduce new colors.
fn vertical(source:&[i32],out:&mut[i32],width:usize,height:usize,radius:usize,red:&mut[i32],green:&mut[i32],blue:&mut[i32],lookup:&[u8]){
    red.fill(0);green.fill(0);blue.fill(0);
    for y in -(radius as isize)..=radius as isize{
        let base=y.clamp(0,(height-1) as isize) as usize*width;
        for x in 0..width{let (r,g,b)=channels(source[base+x]);red[x]+=r;green[x]+=g;blue[x]+=b;}
    }
    for y in 0..height{
        let base=y*width;let leaving=y.saturating_sub(radius)*width;let entering=(y+radius+1).min(height-1)*width;
        for x in 0..width{
            out[base+x]=exact_quotient_color(red[x],green[x],blue[x],lookup);
            let (lr,lg,lb)=channels(source[leaving+x]);let (er,eg,eb)=channels(source[entering+x]);red[x]+=er-lr;green[x]+=eg-lg;blue[x]+=eb-lb;
        }
    }
}

#[cfg(test)]mod tests{
    use super::*;
    fn reference(pixels:&mut[i32],w:usize,h:usize,r:usize){
        let mut temp=vec![0;pixels.len()];let r=r.clamp(1,32);let div=(r*2+1) as i32;
        for _ in 0..3{for horizontal in [true,false]{
            let (source,out)=if horizontal{(&pixels[..],&mut temp[..])}else{(&temp[..],&mut pixels[..])};
            let lines=if horizontal{h}else{w};let length=if horizontal{w}else{h};let stride=if horizontal{1}else{w};
            for line in 0..lines{let base=if horizontal{line*w}else{line};let(mut rr,mut gg,mut bb)=(0,0,0);
                for x in -(r as isize)..=r as isize{let(a,b,c)=channels(source[base+x.clamp(0,(length-1) as isize) as usize*stride]);rr+=a;gg+=b;bb+=c;}
                for x in 0..length{out[base+x*stride]=color(rr,gg,bb,div);let(a,b,c)=channels(source[base+(x+r+1).min(length-1)*stride]);let(d,e,f)=channels(source[base+x.saturating_sub(r)*stride]);rr+=a-d;gg+=b-e;bb+=c-f;}
            }
        }}
    }
    #[test]fn exact_source_windows(){let mut seed=21u32;for (w,h) in [(1,1),(1,29),(33,1),(2,3),(7,11),(37,18),(270,600)]{for r in [0,1,4,16,32,99]{
        let mut input=Vec::new();for _ in 0..w*h{seed=seed.wrapping_mul(1664525).wrapping_add(1013904223);input.push(seed as i32);}let mut expected=input.clone();reference(&mut expected,w,h,r);blur(&mut input,w,h,r).unwrap();assert_eq!(input,expected,"{w}x{h} r{r}");
    }}}
}
