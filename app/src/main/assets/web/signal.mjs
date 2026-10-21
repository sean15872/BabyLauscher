/* BabyLauscher v0.2 – Offline-Akustikmerkmale. Kein validiertes Diagnosemodell. */
export const LABELS = [
  { id: 'hunger', name: 'Hunger', sound: 'Neh', emoji: '🍼', help: 'Zunge bewegt sich beim Saugen nach oben; Hungerzeichen wie Suchbewegungen beachten.' },
  { id: 'muede', name: 'Müdigkeit', sound: 'Owh / Auh', emoji: '🌙', help: 'Gähnen, Wegsehen und Reiben der Augen zusätzlich beobachten.' },
  { id: 'unwohl', name: 'Unwohlsein', sound: 'Heh', emoji: '🧷', help: 'Windel, Kleidung, Umgebungstemperatur und Lage prüfen.' },
  { id: 'bauch', name: 'Bauchweh / Blähungen', sound: 'Eairh', emoji: '🌿', help: 'Angespannter Bauch oder angezogene Beine können weitere Hinweise sein.' },
  { id: 'aufstossen', name: 'Aufstoßen', sound: 'Eh', emoji: '💨', help: 'Nach dem Trinken vorsichtig Gelegenheit zum Bäuerchen geben.' },
  { id: 'naehe', name: 'Nähe / anderes', sound: '–', emoji: '🤍', help: 'Auch Bedürfnis nach Körperkontakt oder Überreizung kommen infrage.' },
];
const SAMPLE_RATE = 12000;
const FRAME_SIZE = 512;
const HOP = 192;
const FFT_BINS = FRAME_SIZE / 2;
const MEL_COUNT = 14;
const EPS = 1e-10;
const hzToMel = hz => 2595 * Math.log10(1 + hz / 700);
const melToHz = mel => 700 * (Math.pow(10, mel / 2595) - 1);

export function downsample(input, inputRate, targetRate = SAMPLE_RATE) {
  if (!input || input.length < 1 || !Number.isFinite(inputRate) || inputRate <= 0) return new Float32Array(0);
  const newLength = Math.floor(input.length * targetRate / inputRate);
  const output = new Float32Array(newLength);
  const step = inputRate / targetRate;
  // Zeitintervallmittelwert vermeidet starkes Aliasing bei hoher Quell-Abtastrate.
  if (step >= 1.5) {
    for (let n = 0; n < newLength; n++) {
      let from = Math.floor(n * step), to = Math.min(input.length, Math.floor((n + 1) * step));
      if (to <= from) to = Math.min(input.length, from + 1);
      let acc = 0;
      for (let i = from; i < to; i++) acc += input[i];
      output[n] = acc / Math.max(1, to - from);
    }
  } else {
    for (let n = 0; n < newLength; n++) {
      const x = n * step, i = Math.floor(x), frac = x - i;
      output[n] = (input[i] || 0) * (1 - frac) + (input[i + 1] || 0) * frac;
    }
  }
  return output;
}

// Iterativer Radix-2-FFT, Spektrum der reellen, Hann-gewichteten Samples.
function powerSpectrum(frame) {
  const n = frame.length, re = new Float64Array(n), im = new Float64Array(n);
  for (let i = 0; i < n; i++) re[i] = frame[i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) { let t = re[i]; re[i] = re[j]; re[j] = t; }
  }
  for (let size = 2; size <= n; size <<= 1) {
    const angle = -2 * Math.PI / size, wr = Math.cos(angle), wi = Math.sin(angle);
    for (let start = 0; start < n; start += size) {
      let ur = 1, ui = 0;
      for (let j = 0; j < size / 2; j++) {
        const k = start + j, h = k + size / 2;
        const vr = re[h] * ur - im[h] * ui, vi = re[h] * ui + im[h] * ur;
        re[h] = re[k] - vr; im[h] = im[k] - vi;
        re[k] += vr; im[k] += vi;
        const nextR = ur * wr - ui * wi;
        ui = ur * wi + ui * wr; ur = nextR;
      }
    }
  }
  const spec = new Float64Array(FFT_BINS + 1);
  for (let k = 0; k <= FFT_BINS; k++) spec[k] = re[k] * re[k] + im[k] * im[k];
  return spec;
}
const melFilters = (() => {
  const lo = hzToMel(130), hi = hzToMel(5600);
  const points = Array.from({ length: MEL_COUNT + 2 }, (_, k) => Math.floor(melToHz(lo + (hi - lo) * k / (MEL_COUNT + 1)) * FRAME_SIZE / SAMPLE_RATE));
  return Array.from({ length: MEL_COUNT }, (_, i) => {
    const weights = [];
    for (let k = points[i]; k <= points[i + 2]; k++) {
      let w = k <= points[i + 1] ? (k - points[i]) / Math.max(1, points[i + 1] - points[i]) : (points[i + 2] - k) / Math.max(1, points[i + 2] - points[i + 1]);
      if (k >= 0 && k <= FFT_BINS && w > 0) weights.push([k, w]);
    }
    return weights;
  });
})();
function pitchOf(frame, sampleRate = SAMPLE_RATE) {
  // Grobe Grundfrequenzschätzung per normalisierter Autokorrelation; keine Phonemerkennung.
  let energy = 0;
  for (let i = 0; i < frame.length; i++) energy += frame[i] * frame[i];
  if (energy < 0.0002) return null;
  let bestLag = 0, best = 0;
  const minLag = Math.floor(sampleRate / 850), maxLag = Math.ceil(sampleRate / 130);
  for (let lag = minLag; lag <= maxLag; lag++) {
    let dot = 0, a = 0, b = 0;
    for (let i = 0; i < frame.length - lag; i += 2) {
      let x = frame[i], y = frame[i + lag]; dot += x * y; a += x * x; b += y * y;
    }
    const c = dot / Math.sqrt(a * b + EPS);
    if (c > best) { best = c; bestLag = lag; }
  }
  return best > 0.52 && bestLag > 0 ? sampleRate / bestLag : null;
}
function mean(a) { return a.length ? a.reduce((s, n) => s + n, 0) / a.length : 0; }
function std(a, m = mean(a)) { return a.length ? Math.sqrt(mean(a.map(x => (x - m) ** 2))) : 0; }
function median(a) { if (!a.length) return 0; const sorted = [...a].sort((a, b) => a - b); return sorted[Math.floor(sorted.length / 2)]; }
const round = (x, n = 2) => Number(x.toFixed(n));

export function analyzeAudio(input, inputRate) {
  if (!input || input.length < 100 || !Number.isFinite(inputRate)) return { ok: false, why: 'Die Aufnahme ist zu kurz.' };
  const mono = downsample(input, inputRate);
  if (mono.length < SAMPLE_RATE * 0.8) return { ok: false, why: 'Bitte mindestens eine Sekunde aufnehmen.' };
  const rms = [], frames = [], maxFrames = Math.floor((mono.length - FRAME_SIZE) / HOP);
  for (let n = 0; n < maxFrames; n++) {
    const start = n * HOP;
    let energy = 0, dc = 0;
    for (let j = 0; j < FRAME_SIZE; j++) dc += mono[start + j];
    dc /= FRAME_SIZE;
    for (let j = 0; j < FRAME_SIZE; j++) { const v = mono[start + j] - dc; energy += v * v; }
    rms.push(Math.sqrt(energy / FRAME_SIZE));
    frames.push(start);
  }
  const peakRms = Math.max(0, ...rms);
  if (peakRms < 0.012) return { ok: false, why: 'Zu leise oder überwiegend Stille. Bitte näher am Baby aufnehmen.' };
  const sorted = [...rms].sort((a, b) => a - b);
  const floor = sorted[Math.floor(sorted.length * 0.15)] || 0;
  const threshold = Math.max(0.009, Math.min(0.08, Math.max(floor * 2.2, peakRms * 0.19)));
  const active = rms.map(v => v > threshold);
  const activeCount = active.reduce((s, v) => s + Number(v), 0);
  if (activeCount < 14) return { ok: false, why: 'Zu wenige deutliche Laute erkannt. Versuch eine ruhigere Umgebung.' };
  const runs = [];
  for (let i = 0; i < active.length; i++) {
    if (active[i] && (i === 0 || !active[i - 1])) runs.push([i, i]);
    else if (active[i]) runs[runs.length - 1][1] = i;
  }
  // Kleine Pausen innerhalb eines Lauts verschmelzen.
  const merged = [];
  for (const run of runs) {
    if (merged.length && run[0] - merged[merged.length - 1][1] < 8) merged[merged.length - 1][1] = run[1];
    else merged.push([...run]);
  }
  const duration = mono.length / SAMPLE_RATE;
  const pitchValues = [], centroidValues = [], melData = [], zcrValues = [], keptRms = [];
  for (let i = 0; i < frames.length; i += 3) {
    if (!active[i]) continue;
    const frame = mono.subarray(frames[i], frames[i] + FRAME_SIZE);
    const fft = powerSpectrum(frame);
    let pow = 0, weighted = 0;
    for (let k = 1; k < fft.length; k++) { pow += fft[k]; weighted += fft[k] * k * SAMPLE_RATE / FRAME_SIZE; }
    centroidValues.push(weighted / Math.max(EPS, pow));
    let zcr = 0;
    for (let j = 1; j < frame.length; j++) if ((frame[j - 1] < 0) !== (frame[j] < 0)) zcr++;
    zcrValues.push(zcr / frame.length);
    const mel = melFilters.map(filter => {
      let e = 0;
      for (const [k, weight] of filter) e += fft[k] * weight;
      return Math.log1p(e);
    });
    const middle = mean(mel);
    melData.push(mel.map(x => Math.max(-5, Math.min(5, x - middle))));
    const pitch = pitchOf(frame);
    if (pitch != null) pitchValues.push(pitch);
    keptRms.push(rms[i]);
  }
  if (melData.length < 5) return { ok: false, why: 'Nicht ausreichend auswertbare Geräusche erkannt.' };
  const pitchMedian = median(pitchValues), pitchSpread = std(pitchValues);
  const meanBands = Array.from({length: MEL_COUNT}, (_, i) => mean(melData.map(m => m[i])));
  const stdBands = Array.from({length: MEL_COUNT}, (_, i) => std(melData.map(m => m[i])));
  const bursts = merged.filter(([a, b]) => b - a >= 4).length;
  const meanBurst = mean(merged.map(([a, b]) => (b - a + 1) * HOP / SAMPLE_RATE));
  const meanRms = mean(keptRms);
  const dynamics = std(keptRms) / Math.max(0.001, meanRms);
  const loudPercent = Math.round(Math.min(100, activeCount / active.length * 100));
  const base = [
    pitchMedian / 600,
    Math.min(3, pitchSpread / 400),
    pitchValues.length / melData.length,
    mean(centroidValues) / 4200,
    mean(zcrValues) / 0.20,
    activeCount / active.length,
    Math.min(3, dynamics),
    Math.min(3, bursts / Math.max(1, duration * 1.2)),
    Math.min(3, meanBurst / 1.5)
  ];
  const signature = [...base, ...meanBands.map(x => x / 4), ...stdBands.map(x => x / 3)].map(x => round(Math.max(-3, Math.min(3, x)), 4));
  return {
    ok: true,
    signature,
    metrics: {
      duration: round(duration, 1),
      loudPercent,
      bursts,
      pitchHz: pitchMedian ? Math.round(pitchMedian) : null,
      pitchVariationHz: Math.round(pitchSpread),
      loudness: meanRms > 0.12 ? 'kräftig' : meanRms > 0.045 ? 'mittel' : 'eher leise',
      rhythm: bursts >= 5 ? 'mehrere kurze Lautfolgen' : bursts >= 2 ? 'wiederholte Lautfolgen' : 'längere zusammenhängende Laute',
    },
    activity: rms.map(v => Math.min(1, v / Math.max(0.015, peakRms)))
  };
}

export function comparePatterns(signature, records, ageWeeks = 6) {
  const relevant = (Array.isArray(records) ? records : []).filter(e =>
    LABELS.some(label => label.id === e.label) && Array.isArray(e.signature) && e.signature.length === signature.length && e.signature.every(Number.isFinite)
  );
  const groups = new Map();
  for (const s of relevant) groups.set(s.label, (groups.get(s.label) || 0) + 1);
  const ready = [...groups].filter(([, count]) => count >= 3).map(([id]) => id);
  if (ready.length < 2) return { ready: false, counts: Object.fromEntries(groups), required: 'Mindestens je 3 bestätigte Beispiele in 2 verschiedenen Kategorien.' };
  const distance = (a, b) => {
    let s = 0, w = 0;
    for (let i = 0; i < a.length; i++) {
      const weight = i < 9 ? 1.4 : i < 23 ? 0.65 : 0.48;
      s += weight * (a[i] - b[i]) ** 2; w += weight;
    }
    return Math.sqrt(s / w);
  };
  const scores = ready.map(label => {
    const distances = relevant.filter(r => r.label === label).map(r => {
      const ageDelta = Math.abs((Number.isFinite(r.ageWeeks) ? r.ageWeeks : ageWeeks) - ageWeeks);
      return distance(signature, r.signature) + Math.min(0.065, ageDelta * 0.005);
    }).sort((a, b) => a - b);
    return { label, score: mean(distances.slice(0, Math.min(3, distances.length))), count: groups.get(label) };
  }).sort((a, b) => a.score - b.score);
  const top = scores[0], runner = scores[1];
  // Konservative Schwelle: Abstände sind KEINE Wahrscheinlichkeiten.
  const separation = runner ? runner.score / Math.max(0.0001, top.score) : 1;
  const reasonable = top.score < 0.34 && separation > 1.15;
  const strong = top.score < 0.22 && separation > 1.35;
  return {
    ready: true,
    suggest: reasonable ? top.label : null,
    cautious: reasonable,
    relativeMatch: strong ? 'Ähnliches Muster' : 'Schwache Ähnlichkeit',
    runner: runner ? runner.label : null,
    scores, count: relevant.length,
    warning: 'Nur Vergleich mit selbst bestätigten Beispielen. Kein Nachweis der Ursache.'
  };
}
