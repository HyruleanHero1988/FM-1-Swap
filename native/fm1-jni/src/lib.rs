//! The emulated FM-1 for FM-SWITCH on Android.
//!
//! [`Machine`] is fm1-emu's browser machine (crates/fm1-web) without the wasm statics: one
//! handle per running firmware, driven from a single Kotlin audio thread. The JNI functions
//! below (`com.fmswitch.emu.Fm1Native`) only move bytes across; every call for a handle must come
//! from the thread that owns it.

use fm1_core::{Bus, Cpu};
use fm1_soc::{machine, Action, Board, Soc};
use std::collections::VecDeque;

mod jni;

const RAM_BASE: u32 = 0x01c0_0000;
const RAM_SIZE: usize = 1 << 20;
pub const SECTOR: usize = 4096;
/// A saved flash sector: its offset (u32, little endian), then its 4096 bytes.
pub const RECORD: usize = SECTOR + 4;
pub const LCD_PIXELS: usize = 240 * 240;
pub const LEDS: usize = 41;

pub struct Machine {
    cpu: Cpu,
    cpu1: Option<Cpu>,
    soc: Soc,
    pending_tick: bool,
    audio: VecDeque<i16>,
    halted: bool,
    flash0: Vec<u8>,
    started: bool,
}

impl Machine {
    /// Boot a whole update package (.fwsc) with flash sectors saved from an earlier run.
    pub fn boot(pkg: &[u8], saved: &[u8], mhz: u32, jit: bool) -> Result<Machine, String> {
        let st = std::panic::catch_unwind(|| fm1_soc::stock_from_package(pkg, None))
            .map_err(|_| "malformed firmware package".to_owned())??;
        let mut soc = Soc::new(RAM_SIZE, st.xip);
        soc.periph.hz = mhz as u64 * 1_000_000;
        soc.periph.skip_idle = true;
        let mut cpu = Cpu::new(st.entry);
        cpu.jit_on = jit;
        cpu.user = true; // the SPL hands cpu0 over in user mode (fm1-core's Cpu::user)
        let params = RAM_BASE + 0x7fc00;
        soc.write32(params, params + 0x40);
        cpu.regs[0] = fm1_soc::stock_boot(&mut soc, &st.flash, params, st.board);
        let flash0 = soc.flash.mem.clone();
        let mut m = Machine { cpu, cpu1: None, soc, pending_tick: false, audio: VecDeque::new(),
            halted: false, flash0, started: false };
        if !saved.is_empty() && !m.restore(saved) {
            return Err("saved flash does not fit this firmware".to_owned());
        }
        Ok(m)
    }

    fn replace_flash(&mut self, at: usize, data: &[u8]) {
        self.soc.flash.mem[at..at + data.len()].copy_from_slice(data);
        let from = at.max(fm1_soc::XIP_FLASH_OFFSET);
        let end = (at + data.len()).min(fm1_soc::XIP_FLASH_OFFSET + self.soc.xip.len());
        if from < end {
            self.soc.xip[from - fm1_soc::XIP_FLASH_OFFSET..end - fm1_soc::XIP_FLASH_OFFSET]
                .copy_from_slice(&data[from - at..end - at]);
        }
        self.soc.flash.revision = self.soc.flash.revision.wrapping_add(1);
    }

    fn restore(&mut self, bytes: &[u8]) -> bool {
        if self.started || bytes.len() % RECORD != 0 || bytes.len() > RECORD * 256 { return false; }
        let mut seen = [false; 256];
        for record in bytes.chunks_exact(RECORD) {
            let at = u32::from_le_bytes(record[..4].try_into().unwrap()) as usize;
            if at % SECTOR != 0 || at >= fm1_soc::FLASH_SIZE || seen[at / SECTOR] { return false; }
            seen[at / SECTOR] = true;
        }
        for record in bytes.chunks_exact(RECORD) {
            let at = u32::from_le_bytes(record[..4].try_into().unwrap()) as usize;
            self.replace_flash(at, &record[4..]);
        }
        true
    }

    /// The sectors the firmware has written since boot, as [`RECORD`]s.
    pub fn flash_changes(&self) -> Vec<u8> {
        let (now, then) = (&self.soc.flash.mem, &self.flash0);
        let mut out = Vec::new();
        for s in 0..now.len().min(then.len()) / SECTOR {
            let range = s * SECTOR..(s + 1) * SECTOR;
            if now[range.clone()] != then[range.clone()] {
                out.extend_from_slice(&((s * SECTOR) as u32).to_le_bytes());
                out.extend_from_slice(&now[range]);
            }
        }
        out
    }

    pub fn flash_busy(&self) -> bool { self.soc.flash.busy() }

    fn run(&mut self, n: u32) {
        let jit = self.cpu.jit_on;
        if let Some(c1) = self.cpu1.as_mut() { c1.profile = self.cpu.profile; }
        let Machine { cpu, cpu1, soc, pending_tick, .. } = self;
        if machine::run_dual(cpu1, soc, n, RAM_SIZE, jit, |soc, k| machine::run_blocks(cpu, soc, k, pending_tick)).is_err() {
            self.halted = true;
        }
    }

    /// Run until the I2S DMA has made `out.len() / 2` stereo frames (at most `max_insns`);
    /// returns the frames that are real audio (the rest of `out` is silence).
    pub fn render(&mut self, out: &mut [i16], max_insns: u32) -> usize {
        self.started = true;
        let frames = out.len() / 2;
        let mut spent = 0u32;
        while self.audio.len() < 2 * frames && spent < max_insns && !self.halted {
            self.run(20_000);
            spent += 20_000;
            self.audio.extend(self.soc.periph.audio.drain(..));
        }
        let have = (self.audio.len() / 2).min(frames);
        for (i, s) in out.iter_mut().enumerate() {
            *s = if i < 2 * have { self.audio.pop_front().unwrap_or(0) } else { 0 };
        }
        have
    }

    /// The 240x240 screen as ARGB_8888; returns its revision.
    pub fn lcd_argb(&self, out: &mut [i32]) -> u32 {
        for (o, &p) in out.iter_mut().zip(self.soc.lcd.fb.iter()) {
            let p = p as u32;
            let (r, g, b) = ((p >> 11) * 255 / 31, ((p >> 5) & 63) * 255 / 63, (p & 31) * 255 / 31);
            *o = (0xff00_0000 | r << 16 | g << 8 | b) as i32;
        }
        self.soc.lcd.revision
    }

    pub fn lcd_revision(&self) -> u32 { self.soc.lcd.revision }

    /// The LEDs' on-fractions since the last call.
    pub fn leds(&mut self, out: &mut [f32]) {
        let l = self.soc.periph.led_take();
        for (o, v) in out.iter_mut().zip(l.iter()) { *o = *v; }
    }

    pub fn key(&mut self, id: u8, down: bool) { self.soc.periph.apply(&Action::Key { id, down }); }
    pub fn enc(&mut self, enc: u8, clicks: i32) { self.soc.periph.apply(&Action::Enc { enc, clicks }); }
    pub fn pot(&mut self, v: u16) { self.soc.periph.apply(&Action::Adc { ch: 4, value: v.min(1023) }); }
    pub fn midi(&mut self, b: u8) { self.soc.periph.apply(&Action::Midi { b }); }
    pub fn board(&self) -> u32 { (self.soc.board == Board::Smk37) as u32 }
    pub fn halted(&self) -> bool { self.halted }
    pub fn ms(&self) -> u64 { self.soc.periph.ms() }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn blank() -> Machine {
        let mut soc = Soc::new(RAM_SIZE, vec![0x55; 0x80000]);
        soc.flash.load(&vec![0xaa; fm1_soc::FLASH_SIZE]);
        let flash0 = soc.flash.mem.clone();
        Machine { cpu: Cpu::new(0x02000120), cpu1: None, soc, pending_tick: false, audio: VecDeque::new(),
            halted: false, flash0, started: false }
    }

    fn record(at: u32, value: u8) -> Vec<u8> {
        let mut bytes = at.to_le_bytes().to_vec();
        bytes.extend(vec![value; SECTOR]);
        bytes
    }

    #[test]
    fn saved_sectors_round_trip() {
        let mut m = blank();
        assert!(m.flash_changes().is_empty());
        let saved = [record(0x74000, 0x12), record(0xf9000, 0x34)].concat();
        assert!(m.restore(&saved));
        assert_eq!(m.flash_changes(), saved);
    }

    #[test]
    fn bad_saves_are_refused_whole() {
        let mut m = blank();
        for bytes in [record(1, 0), vec![0; 10], [record(0, 0), record(0, 1)].concat()] {
            assert!(!m.restore(&bytes));
            assert!(m.flash_changes().is_empty());
        }
    }

    /// FM1_TEST_FWSC=path/to/package.fwsc cargo test --release -- --ignored --nocapture
    #[test]
    #[ignore]
    fn a_real_package_boots_to_sound_and_a_screen() {
        let path = std::env::var("FM1_TEST_FWSC").expect("FM1_TEST_FWSC");
        let pkg = std::fs::read(path).unwrap();
        let mut m = Machine::boot(&pkg, &[], 100, true).unwrap();
        let t0 = std::time::Instant::now();
        let (mut loud, mut out) = (0usize, vec![0i16; 2 * 512]);
        for _ in 0..(44_100 * 4 / 512) {
            assert!(m.render(&mut out, 50_000_000) > 0);
            loud += out.iter().filter(|s| s.unsigned_abs() > 64).count();
            if m.ms() > 1500 && m.ms() < 1600 { m.key(14 + 12, true); }
        }
        let mut lcd = vec![0i32; LCD_PIXELS];
        m.lcd_argb(&mut lcd);
        let lit = lcd.iter().filter(|&&p| p as u32 & 0xffffff != 0).count();
        println!("4 s emulated in {:?}; {loud} loud samples; {lit} lit pixels", t0.elapsed());
        assert!(!m.halted());
        assert!(lit > 1000, "the screen stays dark");
    }

    /// The app's blocks (256 frames, 20 ms of emulated time at most): how much of real time a
    /// second of audio costs on this machine at MHZ (default 240).
    /// FM1_TEST_FWSC=path/to/package.fwsc MHZ=160 cargo test --release real_time -- --ignored --nocapture
    #[test]
    #[ignore]
    fn real_time() {
        let pkg = std::fs::read(std::env::var("FM1_TEST_FWSC").expect("FM1_TEST_FWSC")).unwrap();
        let mhz = std::env::var("MHZ").ok().and_then(|v| v.parse().ok()).unwrap_or(240);
        let mut m = Machine::boot(&pkg, &[], mhz, true).unwrap();
        let mut out = vec![0i16; 2 * 256];
        for second in 0..4 {
            let t0 = std::time::Instant::now();
            let made: usize = (0..44_100 / 256).map(|_| m.render(&mut out, mhz * 20_000)).sum();
            println!("second {second}: {made} frames of audio, load {:.0}%", t0.elapsed().as_secs_f64() * 100.0);
        }
    }

    #[test]
    fn junk_is_not_a_package() {
        assert!(Machine::boot(&[0; 64], &[], 100, false).is_err());
    }
}
