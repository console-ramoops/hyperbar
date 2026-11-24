use crate::gradient::donor_cam;
use material_colors::color::Rgb;

#[derive(Debug,PartialEq,Eq)]
pub enum SeedResult {
    Exact(u32),
    NeedsMedianCut { surviving_colors: usize, filter_level: usize },
    NeedsTieOrdering,
    NeedsFallbackToneBoost,
    InvalidDimensions,
}

static FILTER_MASKS:OnceLock<Vec<u8>>=OnceLock::new();
fn filter_masks()->&'static [u8] {
    FILTER_MASKS.get_or_init(||{
        // Like the original library's init(), classify the finite5-bit RGB grid
        // once. Later artwork changes perform histogram and byte-mask lookups.
        (0..32768u32).map(|index|{
            let color=0xff000000|(((index>>10)&31)<<19)|(((index>>5)&31)<<11)|((index&31)<<3);
            let cam=donor_cam(color);
            let tone=Rgb::new((color>>16)as u8,(color>>8)as u8,color as u8).as_lstar();
            let mut mask=0u8;
            for (level,min_tone) in [20.0,10.0,5.0].into_iter().enumerate(){
                if cam.chroma>=20.0&&tone>=min_tone&&tone<=100.0-min_tone{mask|=1<<level;}
            }
            if cam.chroma>=10.0&&tone>=10.0&&tone<=90.0{mask|=8;}
            mask
        }).collect()
    })
}

/// Conservatively reproduce source-backed image-seed branches. Returning a
/// Needs* result explicitly prevents an approximation from replacing the donor.
/// Authority: getColorResult0x1f1d0, getMainColorHCT0x209f8,
/// getMainColorFromVector0x20468, getMainColorFromVectorFast0x205e0.
/// Scope: whole original album bitmap, both dimensions1..64, thresholds20/20.
pub fn try_seed_fast(pixels:&[u32],width:usize,height:usize)->SeedResult {
    if width==0||height==0||width>64||height>64||pixels.len()!=width*height {
        return SeedResult::InvalidDimensions;
    }
    let filters=filter_masks();
    let mut histogram=vec![0u32;32768];
    for &pixel in pixels {
        let index=(((pixel>>19)&31)<<10)|(((pixel>>11)&31)<<5)|((pixel>>3)&31);
        histogram[index as usize]+=1;
    }
    let mut bins=Vec::new();
    for (index,count) in histogram.into_iter().enumerate() {
        if count==0 {continue;}
        let color=0xff000000|(((index as u32>>10)&31)<<19)|(((index as u32>>5)&31)<<11)|((index as u32&31)<<3);
        bins.push((color,count,filters[index]));
    }
    for level in 0..3 {
        let surviving:Vec<_>=bins.iter().filter(|bin|bin.2&(1<<level)!=0).collect();
        if surviving.is_empty(){continue;}
        if (17..1000).contains(&surviving.len()) {
            return SeedResult::NeedsMedianCut{surviving_colors:surviving.len(),filter_level:level};
        }
        let maximum=surviving.iter().map(|bin|bin.1).max().unwrap();
        let mut winners=surviving.iter().filter(|bin|bin.1==maximum);
        let winner=winners.next().unwrap();
        if winners.next().is_some(){return SeedResult::NeedsTieOrdering;}
        return SeedResult::Exact(winner.0);
    }
    // The original unfiltered fourth-stage vector boost also lowers the chroma
    // filter to10 and checks tone10..90. Gray/empty qualifying sets return the
    // declared native default. An actual boost requires its own HCT comparison.
    if bins.iter().any(|bin|bin.2&8!=0) {
        SeedResult::NeedsFallbackToneBoost
    }else{
        SeedResult::Exact(0xff407ebf)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn quantized_solid_colors_and_achromatic_fallback() {
        assert_eq!(try_seed_fast(&[0xffff0000;16],4,4),SeedResult::Exact(0xfff80000));
        assert_eq!(try_seed_fast(&[0xff00ff00;16],4,4),SeedResult::Exact(0xff00f800));
        assert_eq!(try_seed_fast(&[0xff0000ff;16],4,4),SeedResult::Exact(0xff0000f8));
        assert_eq!(try_seed_fast(&[0xffffffff;16],4,4),SeedResult::Exact(0xff407ebf));
        assert_eq!(try_seed_fast(&[0xff000000;16],4,4),SeedResult::Exact(0xff407ebf));
    }
    #[test]
    fn ambiguous_count_ties_explicitly_require_donor_ordering() {
        assert_eq!(try_seed_fast(&[0xffff0000,0xff0000ff],2,1),SeedResult::NeedsTieOrdering);
    }
    #[test]
    fn unique_dominance_selects_after_hct_filtering() {
        assert_eq!(try_seed_fast(&[0xff000000,0xff000000,0xff000000,0xffff0000],4,1),SeedResult::Exact(0xfff80000));
    }
}

use std::ffi::{c_char,c_int,c_void,CStr,CString};
use std::sync::{Mutex,OnceLock};

#[link(name="dl")]
unsafe extern "C" {
    fn dlopen(filename:*const c_char,flags:c_int)->*mut c_void;
    fn dlsym(handle:*mut c_void,symbol:*const c_char)->*mut c_void;
    fn dlclose(handle:*mut c_void)->c_int;
    fn dlerror()->*const c_char;
}
type Setter=unsafe extern "C" fn(*mut c_void,c_int);
type Init=unsafe extern "C" fn(*mut c_void);
type GetNativeSeed=unsafe extern "C" fn(*mut c_void,*mut c_int,c_int,c_int)->c_int;

struct ExactFallback {
    // Kept loaded for the module lifetime. Calls are serialized by FALLBACK's
    // mutex because the donor reuses mutable histogram/filter state.
    _handle:*mut c_void,
    process:*mut c_void,
    get_seed:GetNativeSeed,
}
unsafe impl Send for ExactFallback {}

static FALLBACK:OnceLock<(String,Mutex<ExactFallback>)>=OnceLock::new();
static LOAD_LOCK:Mutex<()>=Mutex::new(());

fn loader_error(context:&str)->String {
    let error=unsafe{dlerror()};
    if error.is_null(){context.to_owned()}
    else{format!("{context}: {}",unsafe{CStr::from_ptr(error)}.to_string_lossy())}
}
fn required_symbol(handle:*mut c_void,name:&'static [u8])->Result<*mut c_void,String>{
    let symbol=unsafe{dlsym(handle,name.as_ptr().cast())};
    if symbol.is_null(){Err(loader_error("Missing exact palette symbol"))}
    else{Ok(symbol)}
}

/// Bind the packaged original library for seed branches whose native ordering
/// has not been completely reconstructed. dlopen calls its ELF initializers but
/// does not invoke JNI_OnLoad; no MiPalette Java shim or registration is needed.
/// Authority: libMiMainColor.so SHA652eb3fb8850b46a4df0865c1abf4f3ca5b0084d828dceb5ce547be86e1d1852.
/// Original exported C++ receiver/argument ABI validated by the owned oracle.
pub fn initialize_exact_fallback(path:&str)->Result<(),String>{
    if !path.starts_with('/')||!path.ends_with(".so"){
        return Err("Exact palette library requires an absolute .so path".into());
    }
    let _serial=LOAD_LOCK.lock().map_err(|_|"Palette loader lock poisoned")?;
    if let Some((loaded,_))=FALLBACK.get(){
        return if loaded==path{Ok(())}else{Err("Exact palette already loaded from a different path".into())};
    }
    let encoded=CString::new(path).map_err(|_|"Palette library path contains NUL")?;
    let handle=unsafe{dlopen(encoded.as_ptr(),2)}; // Android RTLD_NOW | RTLD_LOCAL
    if handle.is_null(){return Err(loader_error("Could not load exact palette library"));}
    let loaded=(||{
        let process=required_symbol(handle,b"process\0")?;
        let set_tone=required_symbol(handle,b"_ZN9MainColor16setToneThresholdEi\0")?;
        let set_chroma=required_symbol(handle,b"_ZN9MainColor18setChromaThresholdEi\0")?;
        let init=required_symbol(handle,b"_ZN9MainColor4initEv\0")?;
        let get_seed=required_symbol(handle,b"_ZN9MainColor15getMainColorHCTEPiii\0")?;
        // This ELF exports receiver methods, not static free functions.
        let set_tone:Setter=unsafe{std::mem::transmute(set_tone)};
        let set_chroma:Setter=unsafe{std::mem::transmute(set_chroma)};
        let init:Init=unsafe{std::mem::transmute(init)};
        let get_seed:GetNativeSeed=unsafe{std::mem::transmute(get_seed)};
        unsafe{
            set_tone(process,20);
            set_chroma(process,20);
            init(process);
        }
        Ok::<ExactFallback,String>(ExactFallback{_handle:handle,process,get_seed})
    })();
    match loaded{
        Ok(fallback)=>{
            FALLBACK.set((path.to_owned(),Mutex::new(fallback)))
                .map_err(|_|"Exact palette was initialized concurrently".to_owned())?;
            Ok(())
        },
        Err(reason)=>{
            unsafe{dlclose(handle);}
            Err(reason)
        }
    }
}

#[derive(Debug,Clone,Copy,PartialEq,Eq)]
pub struct ArtworkPalette {
    pub seed:u32,
    pub top:u32,
    pub bottom:u32,
    pub used_exact_native_fallback:bool,
}

/// Match both color math and the donor's unported median/tie cases. Android
/// Drawable resizing stays at the framework boundary: supply the original
/// independently clamped1..64 bitmap dimensions and its ARGB pixels.
pub fn artwork_palette(pixels:&[u32],width:usize,height:usize)
    ->Result<ArtworkPalette,String>{
    let attempt=try_seed_fast(pixels,width,height);
    let (seed,used_exact_native_fallback)=match attempt{
        SeedResult::Exact(seed)=>(seed,false),
        SeedResult::InvalidDimensions=>return Err("Palette bitmap must be1..64 in each dimension with exact pixels".into()),
        _=>{
            let (_,state)=FALLBACK.get().ok_or("Exact palette fallback has not been initialized")?;
            let fallback=state.lock().map_err(|_|"Palette fallback state lock poisoned")?;
            // C++ accepts mutable int*, so isolate it from the caller's pixels.
            let mut native_pixels:Vec<c_int>=pixels.iter().map(|&x|x as c_int).collect();
            let seed=unsafe{(fallback.get_seed)(fallback.process,native_pixels.as_mut_ptr(),
                width as c_int,height as c_int)} as u32;
            (seed,true)
        }
    };
    let [top,bottom]=crate::gradient::gradient_from_seed(seed);
    Ok(ArtworkPalette{seed,top,bottom,used_exact_native_fallback})
}
