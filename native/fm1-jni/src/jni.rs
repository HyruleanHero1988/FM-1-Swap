//! `com.fmswitch.emu.Fm1Native`: a handle (a boxed [`Machine`] as a jlong) and plain arrays.

use crate::{Machine, LCD_PIXELS, LEDS};
use jni_sys::{jboolean, jbyteArray, jclass, jfloatArray, jint, jintArray, jlong, jshortArray, jsize, JNIEnv};
use std::ptr;

unsafe fn machine<'a>(h: jlong) -> Option<&'a mut Machine> {
    (h as *mut Machine).as_mut()
}

unsafe fn bytes(env: *mut JNIEnv, a: jbyteArray) -> Vec<u8> {
    if a.is_null() { return Vec::new(); }
    let f = &**env;
    let n = (f.GetArrayLength.unwrap())(env, a) as usize;
    let mut v = vec![0u8; n];
    (f.GetByteArrayRegion.unwrap())(env, a, 0, n as jsize, v.as_mut_ptr() as *mut i8);
    v
}

unsafe fn new_bytes(env: *mut JNIEnv, v: &[u8]) -> jbyteArray {
    let f = &**env;
    let a = (f.NewByteArray.unwrap())(env, v.len() as jsize);
    if !a.is_null() {
        (f.SetByteArrayRegion.unwrap())(env, a, 0, v.len() as jsize, v.as_ptr() as *const i8);
    }
    a
}

/// Boot `pkg` with `saved` flash records (or null); 0 when it does not boot.
#[no_mangle]
pub unsafe extern "system" fn Java_com_fmswitch_emu_Fm1Native_boot(
    env: *mut JNIEnv, _: jclass, pkg: jbyteArray, saved: jbyteArray, mhz: jint, jit: jboolean,
) -> jlong {
    let (pkg, saved) = (bytes(env, pkg), bytes(env, saved));
    match std::panic::catch_unwind(|| Machine::boot(&pkg, &saved, mhz.max(1) as u32, jit != 0)) {
        Ok(Ok(m)) => Box::into_raw(Box::new(m)) as jlong,
        _ => 0,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_fmswitch_emu_Fm1Native_release(_: *mut JNIEnv, _: jclass, h: jlong) {
    if h != 0 { drop(Box::from_raw(h as *mut Machine)); }
}

/// Fill `out` (interleaved stereo i16) and return the frames that are real audio, or -1 if the
/// machine stopped (a fault the emulator cannot go on from).
#[no_mangle]
pub unsafe extern "system" fn Java_com_fmswitch_emu_Fm1Native_render(
    env: *mut JNIEnv, _: jclass, h: jlong, out: jshortArray, max_insns: jint,
) -> jint {
    let Some(m) = machine(h) else { return -1 };
    let f = &**env;
    let n = (f.GetArrayLength.unwrap())(env, out) as usize;
    let mut buf = vec![0i16; n];
    let have = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| m.render(&mut buf, max_insns.max(0) as u32))) {
        Ok(have) => have as jint,
        Err(_) => return -1,
    };
    (f.SetShortArrayRegion.unwrap())(env, out, 0, n as jsize, buf.as_ptr());
    if m.halted() { -1 } else { have }
}

/// The screen's revision; `out` is only filled when that differs from `known`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_fmswitch_emu_Fm1Native_lcd(
    env: *mut JNIEnv, _: jclass, h: jlong, out: jintArray, known: jint,
) -> jint {
    let Some(m) = machine(h) else { return 0 };
    if m.lcd_revision() as jint == known { return known; }
    let mut buf = vec![0i32; LCD_PIXELS];
    let rev = m.lcd_argb(&mut buf);
    ((**env).SetIntArrayRegion.unwrap())(env, out, 0, LCD_PIXELS as jsize, buf.as_ptr());
    rev as jint
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_fmswitch_emu_Fm1Native_leds(env: *mut JNIEnv, _: jclass, h: jlong, out: jfloatArray) {
    let Some(m) = machine(h) else { return };
    let mut buf = [0f32; LEDS];
    m.leds(&mut buf);
    ((**env).SetFloatArrayRegion.unwrap())(env, out, 0, LEDS as jsize, buf.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_fmswitch_emu_Fm1Native_key(_: *mut JNIEnv, _: jclass, h: jlong, id: jint, down: jboolean) {
    if let Some(m) = machine(h) { m.key(id as u8, down != 0) }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_fmswitch_emu_Fm1Native_enc(_: *mut JNIEnv, _: jclass, h: jlong, enc: jint, clicks: jint) {
    if let Some(m) = machine(h) { m.enc(enc as u8, clicks) }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_fmswitch_emu_Fm1Native_pot(_: *mut JNIEnv, _: jclass, h: jlong, v: jint) {
    if let Some(m) = machine(h) { m.pot(v.clamp(0, 1023) as u16) }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_fmswitch_emu_Fm1Native_midi(_: *mut JNIEnv, _: jclass, h: jlong, b: jint) {
    if let Some(m) = machine(h) { m.midi(b as u8) }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_fmswitch_emu_Fm1Native_board(_: *mut JNIEnv, _: jclass, h: jlong) -> jint {
    machine(h).map_or(0, |m| m.board() as jint)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_fmswitch_emu_Fm1Native_ms(_: *mut JNIEnv, _: jclass, h: jlong) -> jlong {
    machine(h).map_or(0, |m| m.ms() as jlong)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_fmswitch_emu_Fm1Native_flashBusy(_: *mut JNIEnv, _: jclass, h: jlong) -> jboolean {
    machine(h).is_some_and(|m| m.flash_busy()) as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_fmswitch_emu_Fm1Native_flashChanges(env: *mut JNIEnv, _: jclass, h: jlong) -> jbyteArray {
    match machine(h) {
        Some(m) => new_bytes(env, &m.flash_changes()),
        None => ptr::null_mut(),
    }
}
