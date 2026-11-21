use crate::{events::Event, input::Engine};
use jni::{
    objects::{JByteArray, JObject, JString},
    sys::{jfloat, jint, jlong, jstring},
    JNIEnv,
};
use std::{
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{LazyLock, Mutex},
};
// Single process-wide input session, serialized by the Kotlin worker.
// No raw engine pointer or borrowed JVM reference crosses a call.
static ENGINE: LazyLock<Mutex<Engine>> = LazyLock::new(|| Mutex::new(Engine::default()));
fn forward_shortcut(session: jlong, command: i32, engine: &Engine) {
    let mut custom = Vec::new();
    let chord: &[i32] = match command {
        4096..=8191 => {
            let key = command & 255;
            if key >= 224 || !input_wifi::supported_key(key) {
                return;
            }
            for i in 0..4 {
                if command & (256 << i) != 0 {
                    custom.push(224 + i);
                }
            }
            custom.push(key);
            &custom
        }
        0 => &[224, 6],       // Ctrl+C
        1 => &[224, 25],      // Ctrl+V
        2 => &[224, 29],      // Ctrl+Z
        3 => &[227, 15],      // Win+L
        4 => &[227, 225, 22], // Win+Shift+S
        _ => return,
    };
    for &key in chord.iter().filter(|key| !engine.keys.contains(key)) {
        crate::wifi::forward(session, Some(&Event::KeyDown(key)), false);
    }
    for &key in chord.iter().rev().filter(|key| !engine.keys.contains(key)) {
        crate::wifi::forward(session, Some(&Event::KeyUp(key)), false);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_inputcontrol_nativebridge_Native_dispatch(
    _: JNIEnv,
    _: JObject,
    op: jint,
    id: jint,
    x: jfloat,
    y: jfloat,
    session: jlong,
) -> jlong {
    catch_unwind(|| {
        let mut engine = ENGINE.lock().ok()?;
        let n = engine.dispatch(op, id, x, y).ok()?;
        if session != 0 && matches!(op, 12 | 15) {
            if engine
                .batch
                .iter()
                .any(|r| matches!(r.event, Event::TextCompositionCancel))
            {
                crate::wifi::forward(session, Some(&Event::TextCompositionCancel), false);
            }
            crate::wifi::forward(session, None, true);
        } else if session != 0 {
            for record in &engine.batch {
                match record.event {
                    Event::Shortcut(command) if op == 16 => {
                        crate::wifi::forward(session, Some(&Event::Shortcut(command)), false)
                    }
                    Event::Shortcut(command) => forward_shortcut(session, command, &engine),
                    Event::KeyDown(k) if !engine.physical_key(k) => {}
                    _ => crate::wifi::forward(session, Some(&record.event), false),
                }
            }
        }
        engine.batch.clear();
        Some(n as jlong)
    })
    .ok()
    .flatten()
    .unwrap_or(-1)
}
#[no_mangle]
pub extern "system" fn Java_io_inputcontrol_nativebridge_Native_composing(
    _: JNIEnv,
    _: JObject,
) -> jint {
    ENGINE
        .lock()
        .ok()
        .and_then(|e| e.composing())
        .map_or(0, |c| c as jint)
}
#[no_mangle]
pub extern "system" fn Java_io_inputcontrol_nativebridge_Native_snapshot(
    env: JNIEnv,
    _: JObject,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let e = ENGINE.lock().ok()?;
        env.new_string(format!("{}\n{}", e.count, e.last_kind))
            .ok()
            .map(|s| s.into_raw())
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_io_inputcontrol_nativebridge_Native_openWifi(
    mut env: JNIEnv,
    _: JObject,
    id: jlong,
    address: JString,
    key: JByteArray,
    pin: JString,
    model: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let addr: String = env.get_string(&address).ok()?.into();
        let expected: String = env.get_string(&pin).ok()?.into();
        let key = env.convert_byte_array(key).ok()?.try_into().ok()?;
        let model: String = env.get_string(&model).ok()?.into();
        crate::wifi::start(
            id,
            addr.parse().ok()?,
            key,
            if expected.is_empty() {
                None
            } else {
                Some(input_wifi::pin(&expected).ok()?)
            },
            model,
        )
        .ok()
    }))
    .ok()
    .flatten()
    .map_or(-1, |_| 0)
}
#[no_mangle]
pub extern "system" fn Java_io_inputcontrol_nativebridge_Native_wifiStatus(
    env: JNIEnv,
    _: JObject,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        env.new_string(crate::wifi::status())
            .ok()
            .map(|s| s.into_raw())
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_io_inputcontrol_nativebridge_Native_confirmWifi(
    mut env: JNIEnv,
    _: JObject,
    id: jlong,
    code: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(c) = env.get_string(&code) {
            crate::wifi::approve(id, &String::from(c))
        }
    }));
}
#[no_mangle]
pub extern "system" fn Java_io_inputcontrol_nativebridge_Native_closeWifi(_: JNIEnv, _: JObject) {
    let _ = catch_unwind(crate::wifi::close);
}

#[no_mangle]
pub extern "system" fn Java_io_inputcontrol_nativebridge_Native_wantFocus(
    _: JNIEnv,
    _: JObject,
    on: jni::sys::jboolean,
) {
    crate::wifi::WANT_FOCUS.store(on != 0, std::sync::atomic::Ordering::Relaxed);
}

#[no_mangle]
pub extern "system" fn Java_io_inputcontrol_nativebridge_Native_dashboard(
    env: JNIEnv,
    _: JObject,
    id: jlong,
    kind: jint,
) -> jstring {
    crate::wifi::dashboard(id, kind as usize)
        .and_then(|s| env.new_string(s).ok())
        .map_or(std::ptr::null_mut(), |s| s.into_raw())
}
