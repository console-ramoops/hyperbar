#![deny(unsafe_op_in_unsafe_fn)]

pub mod capsule;
pub mod motion;
pub mod states;
pub mod gradient;
pub mod palette;
pub mod animation;
pub mod clock;
pub mod clock_card;
pub mod swipe;
pub mod presentation;
pub mod glass;
pub mod glow;
pub mod music_background;
pub mod flashlight;
pub mod wave;
mod pixels;

use jni_sys::{jboolean, jbyteArray, jclass, jdoubleArray, jfloatArray, jintArray, jlong, jlongArray, jstring, JNIEnv};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::sync::atomic::{AtomicI64, Ordering};
use std::ffi::CString;
use std::panic::{catch_unwind, AssertUnwindSafe};

type Env = *mut JNIEnv;
type NativeResult<T> = Result<T, String>;
static NEXT_ANIMATOR: AtomicI64 = AtomicI64::new(1);
static NEXT_PRESENTATION:AtomicI64=AtomicI64::new(1);
static PRESENTATIONS:OnceLock<Mutex<HashMap<i64,presentation::Queue>>>=OnceLock::new();
fn presentations()->&'static Mutex<HashMap<i64,presentation::Queue>>{PRESENTATIONS.get_or_init(||Mutex::new(HashMap::new()))}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeCreatePresentation(env:Env,_class:jclass)->jlong{
    boundary(env,0,||{let id=NEXT_PRESENTATION.fetch_add(1,Ordering::Relaxed);if id<=0{return Err("Presentation handles exhausted".into());}
        presentations().lock().map_err(|_|"Presentation registry poisoned")?.insert(id,presentation::Queue::default());Ok(id)})
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeDestroyPresentation(env:Env,_class:jclass,handle:jlong){
    boundary(env,(),||{presentations().lock().map_err(|_|"Presentation registry poisoned")?.remove(&handle);Ok(())});
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeSyncPresentation(env:Env,_class:jclass,handle:jlong,input:jlongArray,preferred:jlong){
    boundary(env,(),||{let count=array_length(env,input)?;let mut ids=vec![0_i64;count];
        unsafe{((**env).v1_6.GetLongArrayRegion)(env,input,0,count as i32,ids.as_mut_ptr());}
        if exception_pending(env){return Err("Could not read presentation identities".into());}
        let mut registry=presentations().lock().map_err(|_|"Presentation registry poisoned")?;
        let mut queue=registry.get(&handle).cloned().ok_or("Presentation is closed")?;
        queue.sync(&ids,preferred).map_err(str::to_owned)?;registry.insert(handle,queue);Ok(())})
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativePresentationCommand(env:Env,_class:jclass,handle:jlong,command:i32,item:jlong){
    boundary(env,(),||{let mut registry=presentations().lock().map_err(|_|"Presentation registry poisoned")?;
        let mut queue=registry.get(&handle).cloned().ok_or("Presentation is closed")?;
        match command{0=>queue.left(),1=>queue.right(),2=>queue.hide(item),3=>queue.focus(item),_=>return Err("Unknown presentation command".into())};
        queue.validate().map_err(str::to_owned)?;registry.insert(handle,queue);Ok(())})
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativePresentationSnapshot(env:Env,_class:jclass,handle:jlong,output:jlongArray){
    boundary(env,(),||{let registry=presentations().lock().map_err(|_|"Presentation registry poisoned")?;
        let queue=registry.get(&handle).ok_or("Presentation is closed")?;
        if array_length(env,output)?<queue.hidden.len()+3{return Err("Presentation output too small".into());}
        let mut values=vec![queue.big,queue.small,queue.hidden.len() as i64];values.extend_from_slice(&queue.hidden);
        unsafe{((**env).v1_6.SetLongArrayRegion)(env,output,0,values.len() as i32,values.as_ptr());}Ok(())})
}
static ANIMATORS: OnceLock<Mutex<HashMap<i64, animation::Animator>>> = OnceLock::new();
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativePresentationPreview(env:Env,_class:jclass,
    handle:jlong,direction:i32,output:jlongArray){
    boundary(env,(),||{let registry=presentations().lock().map_err(|_|"Presentation registry poisoned")?;
        let queue=registry.get(&handle).ok_or("Presentation is closed")?.preview(direction).map_err(str::to_owned)?;
        if array_length(env,output)?<queue.hidden.len()+3{return Err("Presentation preview output too small".into());}
        let mut values=vec![queue.big,queue.small,queue.hidden.len() as i64];values.extend_from_slice(&queue.hidden);
        unsafe{((**env).v1_6.SetLongArrayRegion)(env,output,0,values.len() as i32,values.as_ptr());}Ok(())});
}
static NEXT_MUSIC_BACKGROUND: AtomicI64 = AtomicI64::new(1);
static NEXT_FLASHLIGHT:AtomicI64=AtomicI64::new(1);
static NEXT_WAVE:AtomicI64=AtomicI64::new(1);
static WAVES:OnceLock<Mutex<HashMap<i64,wave::Wave>>>=OnceLock::new();
fn waves()->&'static Mutex<HashMap<i64,wave::Wave>>{WAVES.get_or_init(||Mutex::new(HashMap::new()))}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeCreateWave(env:Env,_class:jclass)->jlong{
    boundary(env,0,||{let id=NEXT_WAVE.fetch_add(1,Ordering::Relaxed);if id<=0{return Err("Wave handles exhausted".into());}
        waves().lock().map_err(|_|"Wave registry poisoned")?.insert(id,wave::Wave::default());Ok(id)})
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeWaveFrame(env:Env,_class:jclass,handle:jlong,now:jlong,event:i32)->f32{
    boundary(env,0.0,||{waves().lock().map_err(|_|"Wave registry poisoned")?.get_mut(&handle)
        .ok_or("Wave owner is closed")?.frame(now,event).map_err(str::to_owned)})
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeDestroyWave(env:Env,_class:jclass,handle:jlong){
    boundary(env,(),||{waves().lock().map_err(|_|"Wave registry poisoned")?.remove(&handle);Ok(())});
}
static FLASHLIGHTS:OnceLock<Mutex<flashlight::Registry>>=OnceLock::new();
fn flashlights()->&'static Mutex<flashlight::Registry>{FLASHLIGHTS.get_or_init(||Mutex::new(flashlight::Registry::default()))}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeCreateFlashlight(env:Env,_class:jclass,json:jstring)->jlong{
    boundary(env,0,||{
        if json.is_null(){return Err("Missing flashlight keyframes".into());}
        let length=unsafe{((**env).v1_6.GetStringLength)(env,json)};
        if length<=0||length>65_536{return Err("Invalid flashlight keyframe length".into());}
        let mut chars=vec![0;length as usize];
        unsafe{((**env).v1_6.GetStringRegion)(env,json,0,length,chars.as_mut_ptr());}
        if exception_pending(env){return Err("Could not read flashlight keyframes".into());}
        let decoded=String::from_utf16(&chars).map_err(|_|"Invalid flashlight keyframe encoding")?;
        let id=NEXT_FLASHLIGHT.fetch_add(1,Ordering::Relaxed);if id<=0{return Err("Flashlight handles exhausted".into());}
        flashlights().lock().map_err(|_|"Flashlight registry poisoned")?.create(id,&decoded).map_err(str::to_owned)?;Ok(id)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeSetFlashlightVisible(env:Env,_class:jclass,handle:jlong,visible:jboolean,now:jlong){
    boundary(env,(),||{flashlights().lock().map_err(|_|"Flashlight registry poisoned")?.visible(handle,visible,now).map_err(str::to_owned)})
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeFlashlightFrame(env:Env,_class:jclass,handle:jlong,now:jlong,period:jlong,output:jfloatArray)->jboolean{
    boundary(env,false,||{
        if array_length(env,output)?<37{return Err("Flashlight packet output too small".into());}
        let (packet,active)=flashlights().lock().map_err(|_|"Flashlight registry poisoned")?.frame(handle,now,period).map_err(str::to_owned)?;
        unsafe{((**env).v1_6.SetFloatArrayRegion)(env,output,0,37,packet.as_ptr());}
        if exception_pending(env){return Err("Could not write flashlight packet".into());}Ok(active)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeDestroyFlashlight(env:Env,_class:jclass,handle:jlong){
    boundary(env,(),||{flashlights().lock().map_err(|_|"Flashlight registry poisoned")?.destroy(handle);Ok(())});
}
static MUSIC_BACKGROUNDS: OnceLock<Mutex<HashMap<i64, music_background::Background>>> = OnceLock::new();
fn music_backgrounds() -> &'static Mutex<HashMap<i64,music_background::Background>> {
    MUSIC_BACKGROUNDS.get_or_init(||Mutex::new(HashMap::new()))
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeCreateMusicBackground(env:Env,_class:jclass)->jlong {
    boundary(env,0,||{let id=NEXT_MUSIC_BACKGROUND.fetch_add(1,Ordering::Relaxed);
        if id<=0{return Err("Music background handles exhausted".into());}
        music_backgrounds().lock().map_err(|_|"Music background registry poisoned")?
            .insert(id,music_background::Background::default());Ok(id)})
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeDestroyMusicBackground(env:Env,_class:jclass,handle:jlong) {
    boundary(env,(),||{music_backgrounds().lock().map_err(|_|"Music background registry poisoned")?.remove(&handle);Ok(())});
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeMusicBackgroundFrame(env:Env,_class:jclass,
    handle:jlong,now:jlong,event:i32,color:i32,output:jfloatArray)->jboolean {
    boundary(env,false,||{if output.is_null()||array_length(env,output)?<5{return Ok(false);}
        let mut registry=music_backgrounds().lock().map_err(|_|"Music background registry poisoned")?;
        let Some(background)=registry.get_mut(&handle) else{return Ok(false);};
        let mut frame=[0.0;music_background::FRAME_SIZE];let running=background.frame(now,event,color as u32,&mut frame);
        unsafe{((**env).v1_6.SetFloatArrayRegion)(env,output,0,5,frame.as_ptr());}
        if exception_pending(env){return Err("Could not write music background frame".into());}Ok(running)})
}
static NEXT_GLOW_ENGINE: AtomicI64 = AtomicI64::new(1);
static GLOW_ENGINES: OnceLock<Mutex<HashMap<u64, glow::GlowEngine>>> = OnceLock::new();
fn glow_engines() -> &'static Mutex<HashMap<u64, glow::GlowEngine>> {
    GLOW_ENGINES.get_or_init(|| Mutex::new(HashMap::new()))
}

#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeCreateGlow(
    env: Env, _class: jclass,
) -> jlong {
    boundary(env, 0, || {
        let id = NEXT_GLOW_ENGINE.fetch_add(1, Ordering::Relaxed);
        if id <= 0 {
            return Err("Glow engine handles exhausted".into());
        }
        glow_engines()
            .lock()
            .map_err(|_| "Glow registry poisoned")?
            .insert(id as u64, glow::GlowEngine::new());
        Ok(id)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeDestroyGlow(
    env: Env, _class: jclass, handle: jlong,
) {
    boundary(env, (), || {
        if handle > 0 {
            let _ = glow_engines()
                .lock()
                .map_err(|_| "Glow registry poisoned")?
                .remove(&(handle as u64));
        }
        Ok(())
    });
}

#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeGlowFrame(
    env: Env, _class: jclass, handle: jlong, now_ms: jlong, event: i32, animation_scale: f32, output: jfloatArray,
) -> jboolean {
    boundary(env, false, || {
        if handle <= 0 || output.is_null() {
            return Ok(false);
        }
        let len = match array_length(env, output) {
            Ok(l) => l,
            Err(_) => return Ok(false),
        };
        if len < 63 {
            return Ok(false);
        }
        let mut engines = glow_engines().lock().map_err(|_| "Glow registry poisoned")?;
        let engine = match engines.get_mut(&(handle as u64)) {
            Some(e) => e,
            None => return Ok(false),
        };
        let mut packet = [0.0f32; 63];
        let running = engine.frame(now_ms, event, animation_scale, &mut packet);
        unsafe {
            ((**env).v1_6.SetFloatArrayRegion)(env, output, 0, 63, packet.as_ptr());
        }
        if exception_pending(env) {
            return Err("Could not write glow packet to JNI array".into());
        }
        Ok(running)
    })
}
static NEXT_NORMAL_CACHE:AtomicI64=AtomicI64::new(1);
static NORMAL_CACHES:OnceLock<Mutex<HashMap<i64,glass::NormalCache<i64>>>>=OnceLock::new();
fn normal_caches()->&'static Mutex<HashMap<i64,glass::NormalCache<i64>>>{NORMAL_CACHES.get_or_init(||Mutex::new(HashMap::new()))}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeClockCardPlan(env:Env,_class:jclass,style:i32,count_down:bool,paused:bool,has_time:bool,output:jintArray){
    boundary(env,(),||{if array_length(env,output)?<6{return Err("Clock card plan needs six slots".into());}
        let plan=clock_card::plan(style,count_down,paused,has_time).ok_or("Unknown clock card state")?;
        unsafe{((**env).v1_6.SetIntArrayRegion)(env,output,0,6,plan.as_ptr());}Ok(())});
}
fn normal_key(env:Env,input:jintArray)->NativeResult<glass::NormalKey>{
    if array_length(env,input)?<4{return Err("Normal key requires four integers".into());}
    let mut key=[0_i32;4];unsafe{((**env).v1_6.GetIntArrayRegion)(env,input,0,4,key.as_mut_ptr());}
    if exception_pending(env){return Err("Could not read normal key".into());}
    if !(0..=1).contains(&key[0])||key[1]<=0||key[1]>16384||key[2]<=0||key[3]<=0{return Err("Invalid normal cache key".into());}
    Ok(glass::NormalKey{circle:key[0]!=0,radius:key[1],edge_tenths:key[2],power_hundredths:key[3]})
}
fn retired_normals(env:Env,output:jlongArray,retired:Vec<(glass::NormalKey,i64)>)->NativeResult<i32>{
    if array_length(env,output)?<21{return Err("Normal cache removals require 21 slots".into());}
    let ids:Vec<i64>=retired.into_iter().map(|(_,id)|id).collect();
    if !ids.is_empty(){unsafe{((**env).v1_6.SetLongArrayRegion)(env,output,0,ids.len() as i32,ids.as_ptr());}}
    Ok(ids.len() as i32)
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeGlassUniforms(env:Env,_class:jclass,
    width:f32,height:f32,angle:f32,canvas_scale:f32,texture_scale:f32,sdf_scale:f32,mask:bool,output:jfloatArray){
    boundary(env,(),||{if array_length(env,output)?<51{return Err("Glass uniforms require51 slots".into());}
        let values=glass::uniforms(&glass::EXPANDED_TOKEN,[width,height],angle,canvas_scale,texture_scale,sdf_scale,mask).ok_or("Invalid glass uniform geometry")?;
        unsafe{((**env).v1_6.SetFloatArrayRegion)(env,output,0,51,values.as_ptr());}Ok(())});
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeCornerMips(env:Env,_class:jclass,input:jbyteArray,output:jintArray){
    boundary(env,(),||{
        if array_length(env,input)? as usize!=glass::CORNER_MIP_PIXELS*4||array_length(env,output)? as usize!=glass::CORNER_MIP_PIXELS{return Err("Invalid original corner mip chain size".into());}
        let mut bytes=vec![0_u8;glass::CORNER_MIP_PIXELS*4];
        unsafe{((**env).v1_6.GetByteArrayRegion)(env,input,0,bytes.len() as i32,bytes.as_mut_ptr().cast());}
        if exception_pending(env){return Err("Could not read corner mip bytes".into());}
        let pixels=glass::decode_corner_mips(&bytes).ok_or("Invalid corner mip data")?;
        unsafe{((**env).v1_6.SetIntArrayRegion)(env,output,0,pixels.len() as i32,pixels.as_ptr().cast());}Ok(())
    });
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeCreateNormalCache(env:Env,_class:jclass)->jlong{
    boundary(env,0,||{let handle=NEXT_NORMAL_CACHE.fetch_add(1,Ordering::Relaxed);if handle<=0{return Err("Normal cache handles exhausted".into());}
        normal_caches().lock().map_err(|_|"Normal cache registry poisoned")?.insert(handle,glass::NormalCache::default());Ok(handle)})
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeDestroyNormalCache(env:Env,_class:jclass,handle:jlong){
    boundary(env,(),||{normal_caches().lock().map_err(|_|"Normal cache registry poisoned")?.remove(&handle);Ok(())});
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeNormalKey(env:Env,_class:jclass,radius:f32,edge:f32,power:f32,circle:bool,output:jintArray){
    boundary(env,(),||{if array_length(env,output)?<5{return Err("Normal key output requires five slots".into());}
        let key=glass::NormalKey::new(circle,radius,edge,power).ok_or("Invalid normal geometry")?;
        let values=[i32::from(key.circle),key.radius,key.edge_tenths,key.power_hundredths,key.quarter_side() as i32];
        unsafe{((**env).v1_6.SetIntArrayRegion)(env,output,0,5,values.as_ptr());}Ok(())});
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeNormalCacheGet(env:Env,_class:jclass,handle:jlong,input:jintArray)->jlong{
    boundary(env,0,||{let key=normal_key(env,input)?;let mut caches=normal_caches().lock().map_err(|_|"Normal cache registry poisoned")?;
        let cache=caches.get_mut(&handle).ok_or("Normal cache is closed")?;Ok(cache.get(key).copied().unwrap_or(0))})
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeNormalCachePut(env:Env,_class:jclass,handle:jlong,input:jintArray,image:jlong,output:jlongArray)->i32{
    boundary(env,0,||{if array_length(env,output)?<21{return Err("Normal cache removals require 21 slots".into());}
        let key=normal_key(env,input)?;let mut caches=normal_caches().lock().map_err(|_|"Normal cache registry poisoned")?;
        let cache=caches.get_mut(&handle).ok_or("Normal cache is closed")?;let side=key.quarter_side();
        retired_normals(env,output,cache.put(key,(image!=0).then_some(image),side*side*8))})
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeNormalCacheContext(env:Env,_class:jclass,handle:jlong,context:jlong,output:jlongArray)->i32{
    boundary(env,0,||{if array_length(env,output)?<21{return Err("Normal cache removals require 21 slots".into());}
        let mut caches=normal_caches().lock().map_err(|_|"Normal cache registry poisoned")?;let cache=caches.get_mut(&handle).ok_or("Normal cache is closed")?;
        retired_normals(env,output,cache.use_context((context!=0).then_some(context as u64)))})
}

fn animators() -> &'static Mutex<HashMap<i64, animation::Animator>> {
    ANIMATORS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn geometry(env: Env, metrics: jdoubleArray) -> NativeResult<states::Geometry> {
    let count=array_length(env,metrics)?;
    if !matches!(count,21|22) { return Err("State geometry requires 21 or 22 metrics".into()); }
    let mut values = vec![0.0;count];
    unsafe { ((**env).v1_6.GetDoubleArrayRegion)(env, metrics, 0, count as i32, values.as_mut_ptr()); }
    if exception_pending(env) { return Err("Could not read animator geometry".into()); }
    states::Geometry::from_slice(&values).ok_or("Invalid animator geometry".into())
}

#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeCreateAnimator(
    env: Env, _class: jclass) -> jlong {
    boundary(env, 0, || {
        let handle = NEXT_ANIMATOR.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 { return Err("Animator handle space exhausted".into()); }
        animators().lock().map_err(|_| "Animator registry poisoned")?
            .insert(handle, animation::Animator::new());
        Ok(handle)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeDestroyAnimator(
    env: Env, _class: jclass, handle: jlong) {
    boundary(env, (), || {
        animators().lock().map_err(|_| "Animator registry poisoned")?.remove(&handle);
        Ok(())
    });
}

#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeTransition(
    env: Env, _class: jclass, handle: jlong, transition: i32,
    metrics: jdoubleArray, background: i32, scale: f32, transparent: bool) {
    boundary(env, (), || {
        let dimensions = geometry(env, metrics)?;
        let mut owners = animators().lock().map_err(|_| "Animator registry poisoned")?;
        let owner = owners.get_mut(&handle).ok_or("Animator handle is closed")?;
        owner.transition(transition as u32, &dimensions, background, scale, transparent)
            .map_err(str::to_owned)
    });
}

#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeAnimatorFrame(
    env: Env, _class: jclass, handle: jlong, delta_ns: jlong,
    predicted_period_ns: jlong, ratio: f32, output: jdoubleArray) -> jlong {
    boundary(env, 0, || {
        if delta_ns < 0 || predicted_period_ns < 0 || array_length(env, output)? < 84 {
            return Err("Animator requires positive timing and 84 output slots".into());
        }
        let mut owners = animators().lock().map_err(|_| "Animator registry poisoned")?;
        let owner = owners.get_mut(&handle).ok_or("Animator handle is closed")?;
        let snapshot = owner.frame(delta_ns as u64, predicted_period_ns as u64, ratio)
            .map_err(str::to_owned)?;
        let mut values = [0.0; 84];
        values[..28].copy_from_slice(&snapshot.values);
        values[28..56].copy_from_slice(&snapshot.velocities);
        values[56..84].copy_from_slice(&snapshot.targets);
        unsafe { ((**env).v1_6.SetDoubleArrayRegion)(env, output, 0, 84, values.as_ptr()); }
        Ok(snapshot.active_mask as i64)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeSnapAnimator(env:Env,_class:jclass,handle:jlong,kind:i32,metrics:jdoubleArray,background:i32){
    boundary(env,(),||{let geometry=geometry(env,metrics)?;
        animators().lock().map_err(|_|"Animator registry poisoned")?.get_mut(&handle).ok_or("Animator handle is closed")?
            .snap(kind as u32,&geometry,background).map_err(str::to_owned)});
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativePressAnimator(env:Env,_class:jclass,handle:jlong,kind:i32,metrics:jdoubleArray,background:i32,scale:f32,pressed:bool){
    boundary(env,(),||{let geometry=geometry(env,metrics)?;
        animators().lock().map_err(|_|"Animator registry poisoned")?.get_mut(&handle).ok_or("Animator handle is closed")?
            .press(kind as u32,&geometry,background,scale,pressed).map_err(str::to_owned)});
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeSwipeExpanded(env:Env,_class:jclass,handle:jlong,metrics:jdoubleArray,dx:f32,dy:f32)->bool{
    boundary(env,false,||{let geometry=geometry(env,metrics)?;
        animators().lock().map_err(|_|"Animator registry poisoned")?.get_mut(&handle).ok_or("Animator handle is closed")?
            .swipe_expanded(&geometry,dx,dy).map_err(str::to_owned)} )
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeResetSwipe(env:Env,_class:jclass,handle:jlong,metrics:jdoubleArray){
    boundary(env,(),||{let geometry=geometry(env,metrics)?;
        animators().lock().map_err(|_|"Animator registry poisoned")?.get_mut(&handle).ok_or("Animator handle is closed")?
            .reset_swipe(&geometry).map_err(str::to_owned)} )
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeSwipeCapsule(env:Env,_class:jclass,
    handle:jlong,metrics:jdoubleArray,transition:i32,dx:f32){
    boundary(env,(),||{let geometry=geometry(env,metrics)?;
        animators().lock().map_err(|_|"Animator registry poisoned")?.get_mut(&handle).ok_or("Animator handle is closed")?
            .swipe_capsule(&geometry,transition as u32,dx).map_err(str::to_owned)});
}
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeResetCapsule(env:Env,_class:jclass,
    handle:jlong,metrics:jdoubleArray,kind:i32){
    boundary(env,(),||{let geometry=geometry(env,metrics)?;
        animators().lock().map_err(|_|"Animator registry poisoned")?.get_mut(&handle).ok_or("Animator handle is closed")?
            .reset_capsule(&geometry,kind as u32).map_err(str::to_owned)});
}

fn exception_pending(env: Env) -> bool {
    unsafe { ((**env).v1_6.ExceptionCheck)(env) }
}

fn throw(env: Env, reason: &str) {
    if exception_pending(env) { return; }
    let message = CString::new(reason.replace('\0', " ")).unwrap();
    unsafe {
        let class = ((**env).v1_6.FindClass)(env,
            b"java/lang/IllegalArgumentException\0".as_ptr().cast());
        if !class.is_null() {
            ((**env).v1_6.ThrowNew)(env, class, message.as_ptr());
            ((**env).v1_6.DeleteLocalRef)(env, class);
        }
    }
}

fn boundary<T: Copy>(env: Env, default: T,
    work: impl FnOnce() -> NativeResult<T>) -> T {
    if env.is_null() { return default; }
    match catch_unwind(AssertUnwindSafe(work)) {
        Ok(Ok(result)) => result,
        Ok(Err(reason)) => { throw(env, &reason); default },
        Err(_) => {
            throw(env, "Native operation panicked; unwinding contained at JNI boundary");
            default
        }
    }
}

fn array_length(env: Env, array: jni_sys::jarray) -> NativeResult<usize> {
    if array.is_null() { return Err("Null native array".into()); }
    let length = unsafe { ((**env).v1_6.GetArrayLength)(env, array) };
    if exception_pending(env) { return Err("Could not read native array length".into()); }
    Ok(length as usize)
}

#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeInitializePalette(
    env: Env, _class: jclass, path: jstring) {
    boundary(env, (), || {
        if path.is_null() { return Err("Missing exact palette library path".into()); }
        let length = unsafe { ((**env).v1_6.GetStringLength)(env, path) };
        if length <= 0 || length > 4096 { return Err("Invalid palette library path length".into()); }
        let mut chars = vec![0; length as usize];
        unsafe { ((**env).v1_6.GetStringRegion)(env, path, 0, length, chars.as_mut_ptr()); }
        if exception_pending(env) { return Err("Could not read palette path".into()); }
        let decoded = String::from_utf16(&chars).map_err(|_| "Invalid palette path encoding")?;
        palette::initialize_exact_fallback(&decoded)
    });
}

#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeArtworkPalette(
    env: Env, _class: jclass, image: jintArray, width: i32, height: i32,
    output: jintArray) {
    boundary(env, (), || {
        if width <= 0 || height <= 0 || width > 64 || height > 64
            || array_length(env, image)? != (width * height) as usize
            || array_length(env, output)? < 4 {
            return Err("Palette requires a 1..64 pixel image and four outputs".into());
        }
        let length = (width * height) as usize;
        let mut pixels = vec![0_i32; length];
        unsafe { ((**env).v1_6.GetIntArrayRegion)(env, image, 0,
            length as i32, pixels.as_mut_ptr()); }
        if exception_pending(env) { return Err("Could not read artwork pixels".into()); }
        let colors: Vec<u32> = pixels.into_iter().map(|x| x as u32).collect();
        let selected = palette::artwork_palette(&colors, width as usize, height as usize)?;
        let result = [selected.seed as i32, selected.top as i32, selected.bottom as i32,
            i32::from(selected.used_exact_native_fallback)];
        unsafe { ((**env).v1_6.SetIntArrayRegion)(env, output, 0, 4, result.as_ptr()); }
        Ok(())
    });
}

/// The runtime geometry measurements feed the verified donor state factory.
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeState(
    env: Env, _class: jclass, kind: i32, metrics: jdoubleArray,
    background: i32, output: jdoubleArray) {
    boundary(env, (), || {
        if array_length(env, output)? < 28 {
            return Err("State factory requires 28 outputs".into());
        }
        let geometry = geometry(env,metrics)?;
        let result = states::factory(kind as u32, &geometry, background)
            .ok_or("Invalid donor factory state")?;
        unsafe { ((**env).v1_6.SetDoubleArrayRegion)(env, output, 0, 28, result.as_ptr()); }
        Ok(())
    });
}

/// Each of 28 rows is damping,response,mass,delayMs,linearDurationMs,explicit.
/// The native code preserves unscaled default easing for implicit properties.
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeChannels(
    env: Env, _class: jclass, transition: i32, animation_scale: f32,
    transparent: bool, output: jdoubleArray) {
    boundary(env, (), || {
        if array_length(env, output)? < 28 * 6 {
            return Err("Channel configuration requires 168 outputs".into());
        }
        let mut result = [0.0; 28 * 6];
        for channel in 0..28 {
            let selected = motion::channel_config(transition as u32,
                channel as u32, transparent).ok_or("Invalid donor transition")?;
            let spring = motion::preset(selected.config.preset,
                if selected.explicit { animation_scale } else { 1.0 })
                .ok_or("Invalid donor animation scale")?;
            let offset = channel * 6;
            result[offset..offset + 6].copy_from_slice(&[
                spring.damping, spring.response, spring.mass,
                selected.config.delay_ms as f64,
                selected.config.linear_duration_ms as f64,
                if selected.explicit { 1.0 } else { 0.0 }]);
        }
        unsafe { ((**env).v1_6.SetDoubleArrayRegion)(env, output, 0, 168, result.as_ptr()); }
        Ok(())
    });
}

/// Pixel dimensions are supplied in the constructor order documented in the
/// Java bridge. The float limit calculations stay in Rust with JVM semantics.
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeCapsule(
    env: Env, _class: jclass, dimensions: jintArray, limits: jfloatArray,
    rtl: bool, folded: bool, output: jintArray) {
    boundary(env, (), || {
        if array_length(env, dimensions)? != 9 || array_length(env, limits)? != 3
            || array_length(env, output)? < 9 {
            return Err("Capsule requires 9 dimensions, 3 limits and 9 outputs".into());
        }
        let mut dims = [0; 9];
        let mut lim = [0.0; 3];
        unsafe {
            ((**env).v1_6.GetIntArrayRegion)(env, dimensions, 0, 9, dims.as_mut_ptr());
            if exception_pending(env) { return Err("Could not read capsule dimensions".into()); }
            ((**env).v1_6.GetFloatArrayRegion)(env, limits, 0, 3, lim.as_mut_ptr());
        }
        if exception_pending(env) { return Err("Could not read capsule limits".into()); }
        let result = capsule::calculate_phone_capsule(&capsule::CapsuleInput {
            left_content_width: dims[0], right_content_width: dims[1],
            cutout_width: dims[2], screen_width: dims[3], screen_height: dims[4],
            small_width: dims[5], gap: dims[6], min_big_width: dims[7],
            margin_width: dims[8], max_width: lim[0], clock_width: lim[1],
            battery_width: lim[2], rtl, fold_screen_layout_large: folded,
        });
        let out = [result.big_width, result.left_width, result.right_width,
            result.big_x, result.margin_width, result.big_width_with_small,
            result.left_width_with_small, result.right_width_with_small,
            result.big_x_with_small];
        unsafe { ((**env).v1_6.SetIntArrayRegion)(env, output, 0, 9, out.as_ptr()); }
        Ok(())
    });
}

/// Samples several independent donor springs in one JNI call. Input rows are
/// value,target,velocity,damping,response,mass,minVisibleChange,firstFrame.
/// Output rows are value,velocity,equilibrium; validation precedes all writes.
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeSpringBatch(
    env: Env, _class: jclass, input: jdoubleArray, count: i32, dt: f64,
    output: jdoubleArray) -> bool {
    boundary(env, false, || {
        if count < 0 || count > 128 || !dt.is_finite() || dt < 0.0 || dt > 1.0 {
            return Err("Invalid native spring count or time step".into());
        }
        let count = count as usize;
        if array_length(env, input)? != count * 8
            || array_length(env, output)? < count * 3 {
            return Err("Native spring array dimensions do not match count".into());
        }
        // Bounded stack buffers avoid heap allocation or Java pinning per frame.
        let mut source = [0.0; 128 * 8];
        let mut result = [0.0; 128 * 3];
        unsafe {
            ((**env).v1_6.GetDoubleArrayRegion)(env, input, 0,
                (count * 8) as i32, source.as_mut_ptr());
        }
        if exception_pending(env) { return Err("Could not read native spring inputs".into()); }
        let mut running = false;
        for index in 0..count {
            let row = &source[index * 8..index * 8 + 8];
            if !row.iter().all(|value| value.is_finite()) || row[6] < 0.0 {
                return Err("Native spring values and thresholds must be finite".into());
            }
            let spring = motion::Spring::new(row[3], row[4], row[5])
                .ok_or("Invalid donor spring parameters")?;
            let sampled = spring.folme_frame(row[0], row[1], row[2], dt, row[7] != 0.0);
            if !sampled.value.is_finite() || !sampled.velocity.is_finite() {
                return Err("Native spring produced a non-finite state".into());
            }
            let finished = motion::at_equilibrium(sampled.value, row[1],
                sampled.velocity, row[6] as f32);
            result[index * 3] = if finished { row[1] } else { sampled.value };
            result[index * 3 + 1] = if finished { 0.0 } else { sampled.velocity };
            result[index * 3 + 2] = if finished { 1.0 } else { 0.0 };
            running |= !finished;
        }
        unsafe {
            ((**env).v1_6.SetDoubleArrayRegion)(env, output, 0,
                (count * 3) as i32, result.as_ptr());
        }
        Ok(running)
    })
}

/// Keeps the previously validated Rust screenshot blur's six integer sweeps.
#[no_mangle]
pub extern "system" fn Java_com_alpha269_hyperisland_island_HyperOsNative_nativeBlurArgb(
    env: Env, _class: jclass, array: jintArray, width: i32, height: i32,
    radius: i32) {
    boundary(env, (), || {
        if width <= 0 || height <= 0 {
            return Err("Invalid screenshot dimensions".into());
        }
        let length = (width as usize).checked_mul(height as usize)
            .ok_or("Invalid screenshot dimensions")?;
        if array_length(env, array)? != length || length > i32::MAX as usize {
            return Err("Screenshot array dimensions do not match".into());
        }
        pixels::blur_with(width as usize, height as usize, radius.clamp(1, 32) as usize,
            |out| {
                unsafe { ((**env).v1_6.GetIntArrayRegion)(env, array, 0,
                    length as i32, out.as_mut_ptr()); }
                if exception_pending(env) { Err("Could not read screenshot".into()) }
                else { Ok(()) }
            },
            |out| {
                unsafe { ((**env).v1_6.SetIntArrayRegion)(env, array, 0,
                    length as i32, out.as_ptr()); }
                if exception_pending(env) { Err("Could not write screenshot".into()) }
                else { Ok(()) }
            })
    });
}
