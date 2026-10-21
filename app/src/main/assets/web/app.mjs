import { LABELS, analyzeAudio, comparePatterns } from './signal.mjs';

const $ = id => document.getElementById(id);
const STORAGE = 'babylauscher-web-v02'; // Gleicher Schluessel wie Web v0.2, keine Cloud-Synchronisierung
const AGE_STORAGE = 'babylauscher-web-age';
const MAX_SECONDS = 8;
const MAX_EXAMPLES = 240;
let saved = loadExamples();
let analysis = null;
let chosenCause = null;
let recording = null;
let busy = false;
let animationId = 0;
let timeoutId = null;
let startTime = 0;
let resultActivity = null;
let toastTimeout;
let wavePhase = 0;

function loadExamples() {
  try {
    const o = JSON.parse(localStorage.getItem(STORAGE) || '[]');
    return Array.isArray(o) ? o.slice(-MAX_EXAMPLES).filter(r => LABELS.some(l => l.id === r.label) && Array.isArray(r.signature) && r.signature.length === 37 && r.signature.every(Number.isFinite)) : [];
  } catch { return []; }
}
function saveExamples() {
  try { localStorage.setItem(STORAGE, JSON.stringify(saved)); return true; }
  catch { toast('Lokal konnte nicht gespeichert werden. Bitte Browser-Speicher prüfen.'); return false; }
}
function labelFor(id) { return LABELS.find(l => l.id === id) || LABELS[LABELS.length - 1]; }
function toast(message) {
  const element = $('toast'); element.textContent = message; element.classList.remove('hidden');
  clearTimeout(toastTimeout); toastTimeout = setTimeout(() => element.classList.add('hidden'), 4300);
}
function setTitle(text, description) { $('recordHeadline').textContent = text; $('recordDescription').textContent = description; }
function setRecordingUI(on) {
  document.body.classList.toggle('is-recording', on);
  $('recordActionText').textContent = on ? 'Aufnahme stoppen' : 'Aufnahme starten';
  $('recordActionIcon').textContent = on ? '■' : '◉';
  $('recState').textContent = on ? 'Aufnahme läuft' : 'Bereit';
  $('recordButton').setAttribute('aria-label', on ? 'Aufnahme stoppen' : 'Aufnahme starten');
}
function setupLists() {
  LABELS.forEach(l => {
    const cause = document.createElement('button');
    cause.className = 'cause-choice'; cause.type = 'button'; cause.dataset.id = l.id;
    const icon = document.createElement('strong'); icon.textContent = l.emoji;
    const caption = document.createElement('span'); caption.textContent = l.name;
    cause.append(icon, caption);
    cause.addEventListener('click', () => {
      chosenCause = l.id;
      document.querySelectorAll('.cause-choice').forEach(b => b.classList.toggle('is-selected', b.dataset.id === chosenCause));
      $('saveExample').disabled = !analysis?.ok;
    });
    $('causeGrid').append(cause);
    if (l.sound !== '–') {
      const button = document.createElement('button'); button.className = 'sound-item'; button.type = 'button';
      const div = document.createElement('div'); div.className = 'sound-icon'; div.textContent = l.emoji;
      const copy = document.createElement('div'); copy.className = 'sound-copy';
      const strong = document.createElement('strong'); strong.textContent = l.sound;
      const small = document.createElement('small'); small.textContent = l.name;
      copy.append(strong, small);
      const arrow = document.createElement('span'); arrow.className = 'sound-arrow'; arrow.textContent = '›';
      div.setAttribute('aria-hidden', 'true'); button.append(div, copy, arrow);
      button.addEventListener('click', () => {
        $('dialogTitle').textContent = `${l.sound} – ${l.name}`;
        $('dialogText').textContent = l.help;
        $('infoDialog').showModal();
      });
      $('soundList').append(button);
    }
  });
}
function refreshLearning() {
  const counts = Object.fromEntries(LABELS.map(l => [l.id, saved.filter(x => x.label === l.id).length]));
  const ready = LABELS.filter(l => counts[l.id] >= 3).length;
  $('learningTotal').textContent = `${saved.length} Beispiele`;
  $('totalStored').textContent = String(saved.length);
  $('progressCount').textContent = `${ready}/6 Kategorien`;
  $('progressInfo').textContent = ready >= 2 ? 'Ein persönlicher Mustervergleich ist jetzt möglich.' : `Für Vergleiche: je 3 Beispiele in mindestens 2 Kategorien (${ready}/2 bereit).`;
  $('progressBar').style.width = `${Math.min(100, ready / 2 * 100)}%`;
  const list = $('history'); list.replaceChildren();
  for (const r of saved.slice(-7).reverse()) {
    const row = document.createElement('div'); row.className = 'history-item';
    const d = new Date(r.time);
    const name = document.createElement('span'); name.textContent = `${labelFor(r.label).emoji}  ${labelFor(r.label).name}`;
    const date = document.createElement('small'); date.textContent = Number.isNaN(d.getTime()) ? '' : d.toLocaleString('de-DE', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
    row.append(name, date); list.append(row);
  }
  if (!saved.length) { const p = document.createElement('p'); p.textContent = 'Noch keine Beispiele gespeichert.'; list.append(p); }
}
function applyResult(a) {
  analysis = a;
  $('resultTag').textContent = a.ok ? 'Analyse abgeschlossen' : 'Nicht auswertbar';
  $('resultEmpty').classList.toggle('hidden', Boolean(a.ok));
  $('resultContent').classList.toggle('hidden', !a.ok);
  $('saveExample').disabled = !a.ok || !chosenCause;
  if (!a.ok) {
    $('resultEmpty').querySelector('h3').textContent = 'Noch kein klares Tonsignal';
    $('resultEmpty').querySelector('p').textContent = a.why || 'Bitte erneut aufnehmen.';
    setTitle('Erneut versuchen', a.why || 'Keine auswertbare Aufnahme.');
    resultActivity = null;
    return;
  }
  setTitle('Aufnahme ausgewertet', 'Du kannst die Beobachtung nun bestätigen oder eine weitere Aufnahme starten.');
  const m = a.metrics;
  $('metricPitch').textContent = m.pitchHz ? `ca. ${m.pitchHz} Hz` : 'nicht bestimmbar';
  $('metricBursts').textContent = String(m.bursts);
  $('metricRhythm').textContent = m.rhythm;
  const comp = comparePatterns(a.signature, saved, Number($('age').value));
  if (!comp.ready) {
    $('matchTitle').textContent = 'Noch nicht eindeutig';
    $('matchText').textContent = 'Ohne genügend bestätigte Beispiele kann die App keine persönliche Ähnlichkeit erkennen. Sie bestimmt Hunger, Bauchweh usw. nicht aus dem Geräusch allein.';
  } else if (!comp.suggest) {
    $('matchTitle').textContent = 'Kein klares persönliches Muster';
    $('matchText').textContent = 'Die Geräusche passen nicht deutlich besser zu einer gespeicherten Kategorie als zu den anderen. Bitte weitere Hinweise beachten.';
  } else {
    const l = labelFor(comp.suggest), alt = comp.runner ? labelFor(comp.runner) : null;
    $('matchTitle').textContent = `Ähnelt bisherigen Beispielen: ${l.name}`;
    $('matchText').textContent = `Ein akustischer Mustervergleich zeigt ${comp.relativeMatch.toLowerCase()} mit deinen Beispielen für „${l.name}“. Das ist keine sichere Ursache${alt ? `; auch „${alt.name}“ kommt als Vergleich infrage` : ''}.`;
  }
  resultActivity = a.activity;
}
function joinChunks(chunks, maxLength) {
  const total = Math.min(maxLength, chunks.reduce((n, c) => n + c.length, 0));
  const result = new Float32Array(total); let offset = 0;
  for (const c of chunks) {
    if (offset >= total) break;
    const n = Math.min(c.length, total - offset);
    result.set(c.subarray(0, n), offset); offset += n;
  }
  return result;
}
async function startRecording() {
  if (busy || recording) return;
  if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    toast('Mikrofon nicht verfügbar. Bitte Android-Mikrofonberechtigung prüfen.');
    setTitle('Mikrofon nicht verfügbar', 'Bitte die Berechtigung der BabyLauscher-App prüfen.');
    return;
  }
  busy = true;
  let stream = null, ctx = null;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false }, video: false });
    ctx = new (window.AudioContext || window.webkitAudioContext)();
    if (ctx.state === 'suspended') await ctx.resume();
    const source = ctx.createMediaStreamSource(stream);
    const analyser = ctx.createAnalyser(); analyser.fftSize = 2048; analyser.smoothingTimeConstant = .4;
    const silent = ctx.createGain(); silent.gain.value = 0;
    const chunks = [];
    let node, fallback = false;
    source.connect(analyser);
    if (ctx.audioWorklet && typeof AudioWorkletNode !== 'undefined') {
      try {
        await ctx.audioWorklet.addModule('./capture-worklet.js');
        node = new AudioWorkletNode(ctx, 'baby-capture');
        node.port.onmessage = ev => { if (ev.data instanceof Float32Array) chunks.push(ev.data); };
        analyser.connect(node); node.connect(silent);
      } catch (e) { console.warn('AudioWorklet nicht verfügbar, nutze ScriptProcessor:', e); fallback = true; }
    } else fallback = true;
    if (fallback) {
      node = ctx.createScriptProcessor(2048, 1, 1);
      node.onaudioprocess = ev => { const current = ev.inputBuffer.getChannelData(0); chunks.push(new Float32Array(current)); };
      analyser.connect(node); node.connect(silent);
    }
    silent.connect(ctx.destination);
    recording = { ctx, stream, node, chunks, analyser, rate: ctx.sampleRate, fallback };
    startTime = performance.now();
    setRecordingUI(true);
    setTitle('Ich lausche …', 'Halte das Handy möglichst nah ans Baby, ohne dass andere Personen sprechen.');
    timeoutId = setTimeout(() => stopRecording(), MAX_SECONDS * 1000);
  } catch (e) {
    stream?.getTracks().forEach(t => t.stop());
    if (ctx) try { await ctx.close(); } catch {}
    const detail = e?.name === 'NotAllowedError' ? 'Mikrofonberechtigung im Browser erlauben.' : e?.name === 'NotFoundError' ? 'Es wurde kein Mikrofon gefunden.' : 'Mikrofon konnte nicht gestartet werden.';
    toast(detail);
    setTitle('Aufnahme nicht möglich', detail);
    console.warn('Aufnahmefehler:', e);
  } finally { busy = false; }
}
async function stopRecording() {
  if (!recording || busy) return;
  busy = true;
  clearTimeout(timeoutId);
  const r = recording; recording = null;
  setRecordingUI(false);
  setTitle('Geräusche werden ausgewertet', 'Die Auswertung läuft nur auf diesem Gerät.');
  try {
    if (!r.fallback) { r.node.port.postMessage('stop'); await new Promise(resolve => setTimeout(resolve, 110)); }
    else r.node.onaudioprocess = null;
    r.stream.getTracks().forEach(t => t.stop());
    await r.ctx.close();
    const clip = joinChunks(r.chunks, Math.floor(r.rate * MAX_SECONDS));
    const duration = clip.length / r.rate;
    $('timer').innerHTML = `${duration.toFixed(1).replace('.', ',')} <span>/ 8,0 Sekunden</span>`;
    applyResult(analyzeAudio(clip, r.rate));
  } catch (e) {
    toast('Die Aufnahme konnte nicht ausgewertet werden.');
    console.error(e);
  } finally { busy = false; }
}
async function importAudio(file) {
  if (!file || recording || busy) return;
  if (file.size > 25 * 1024 * 1024) return toast('Datei zu groß (maximal 25 MB).');
  busy = true;
  let ctx;
  try {
    setTitle('Audiodatei wird geöffnet …', 'Die Datei bleibt auf deinem Gerät.');
    ctx = new (window.AudioContext || window.webkitAudioContext)();
    const content = await file.arrayBuffer();
    const buffer = await ctx.decodeAudioData(content);
    const n = Math.min(buffer.length, Math.floor(buffer.sampleRate * MAX_SECONDS));
    const mono = new Float32Array(n);
    const channels = Math.min(2, buffer.numberOfChannels);
    for (let c = 0; c < channels; c++) {
      const samples = buffer.getChannelData(c);
      for (let i = 0; i < n; i++) mono[i] += samples[i] / channels;
    }
    applyResult(analyzeAudio(mono, buffer.sampleRate));
    const duration = n / buffer.sampleRate;
    $('timer').innerHTML = `${duration.toFixed(1).replace('.', ',')} <span>/ 8,0 Sekunden</span>`;
    toast('Audiodatei lokal untersucht (erste 8 Sekunden).');
  } catch (e) {
    setTitle('Datei konnte nicht geöffnet werden', 'Bitte eine Audio-Datei wie WAV, MP3 oder M4A verwenden.');
    toast('Der Browser konnte dieses Audioformat nicht dekodieren.');
    console.warn(e);
  } finally { if (ctx) try { await ctx.close(); } catch {} busy = false; $('fileInput').value = ''; }
}
function toggleRecord() { if (recording) stopRecording(); else startRecording(); }
function renderWave(time = 0) {
  const canvas = $('wave'), ctx = canvas.getContext('2d');
  if (!ctx) return;
  const rect = canvas.getBoundingClientRect();
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const w = Math.max(200, Math.floor(rect.width * dpr)), h = Math.max(50, Math.floor(rect.height * dpr));
  if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
  ctx.clearRect(0, 0, w, h);
  ctx.strokeStyle = '#34445b'; ctx.lineWidth = dpr; ctx.beginPath(); ctx.moveTo(0, h/2); ctx.lineTo(w, h/2); ctx.stroke();
  const barCount = Math.max(32, Math.floor(w / (5 * dpr)));
  const values = new Float32Array(barCount);
  if (recording?.analyser) {
    const data = new Float32Array(recording.analyser.fftSize);
    recording.analyser.getFloatTimeDomainData(data);
    for (let i = 0; i < barCount; i++) {
      const a = Math.floor(i * data.length / barCount), b = Math.floor((i+1) * data.length / barCount);
      let energy = 0;
      for (let j = a; j < b; j++) energy += data[j] * data[j];
      values[i] = Math.min(1, Math.sqrt(energy / Math.max(1, b - a)) * 4);
    }
  } else if (resultActivity?.length) {
    for (let i = 0; i < barCount; i++) {
      const at = Math.floor(i * resultActivity.length / barCount);
      values[i] = resultActivity[at] * .85;
    }
  } else {
    wavePhase += 0.02;
    for (let i = 0; i < barCount; i++) values[i] = .06 + .06 * (Math.sin(i * .41 + wavePhase) + 1) / 2;
  }
  const grad = ctx.createLinearGradient(0, 0, w, 0); grad.addColorStop(0, '#78dbc9'); grad.addColorStop(.55, '#9dc8dd'); grad.addColorStop(1, '#f6b5bb');
  ctx.fillStyle = grad;
  const barWidth = Math.max(2, w / barCount * .48), gap = w / barCount;
  for (let i = 0; i < barCount; i++) {
    const ht = Math.max(2 * dpr, values[i] * h * .83), x = i * gap + (gap - barWidth) / 2;
    ctx.beginPath(); ctx.roundRect(x, h / 2 - ht / 2, barWidth, ht, barWidth / 2); ctx.fill();
  }
  if (recording) {
    const seconds = Math.min(MAX_SECONDS, (performance.now() - startTime) / 1000);
    $('timer').innerHTML = `${seconds.toFixed(1).replace('.', ',')} <span>/ 8,0 Sekunden</span>`;
  }
  animationId = requestAnimationFrame(renderWave);
}
function initialize() {
  setupLists();
  try { const storedAge = localStorage.getItem(AGE_STORAGE); const age = storedAge === null ? NaN : Number(storedAge); if (Number.isInteger(age) && age >= 0 && age <= 13) $('age').value = String(age); } catch {}
  $('age').addEventListener('change', () => { try { localStorage.setItem(AGE_STORAGE, $('age').value); } catch {} if (analysis?.ok) applyResult(analysis); });
  refreshLearning();
  $('recordButton').addEventListener('click', toggleRecord);
  $('recordAction').addEventListener('click', toggleRecord);
  $('fileInput').addEventListener('change', e => importAudio(e.target.files?.[0]));
  $('saveExample').addEventListener('click', () => {
    if (!analysis?.ok || !chosenCause || busy) return;
    const entry = { label: chosenCause, signature: analysis.signature, ageWeeks: Number($('age').value), time: new Date().toISOString() };
    saved.push(entry); if (saved.length > MAX_EXAMPLES) saved = saved.slice(-MAX_EXAMPLES);
    if (!saveExamples()) { saved.pop(); return; }
    chosenCause = null;
    document.querySelectorAll('.cause-choice').forEach(b => b.classList.remove('is-selected'));
    $('saveExample').disabled = true;
    refreshLearning();
    applyResult(analysis);
    toast('Beispiel gespeichert – danke!');
    // Die aktuelle Aufnahme wird nicht mehrfach unter verschiedenen Labels gespeichert.
    analysis = null;
    $('saveExample').disabled = true;
  });
  $('clearData').addEventListener('click', () => $('deleteDialog').showModal());
  $('deleteDialog').addEventListener('close', () => {
    if ($('deleteDialog').returnValue !== 'delete') return;
    saved = [];
    try { localStorage.removeItem(STORAGE); } catch {}
    refreshLearning();
    if (analysis?.ok) applyResult(analysis);
    toast('Alle Lernbeispiele gelöscht.');
    $('deleteDialog').returnValue = '';
  });
  $('resetChecks').addEventListener('click', () => { document.querySelectorAll('.checklist input').forEach(x => x.checked = false); });
  document.addEventListener('visibilitychange', () => { if (document.hidden && recording) stopRecording(); });
  window.addEventListener('pagehide', () => { if (recording) { recording.stream.getTracks().forEach(t => t.stop()); recording.ctx.close(); recording = null; } cancelAnimationFrame(animationId); });
  renderWave();
  // In der APK liegen alle Webdateien bereits im App-Paket. Kein Service Worker nötig.
  window.addEventListener('babylauscher:pause', () => { if (recording) stopRecording(); });
}
initialize();
