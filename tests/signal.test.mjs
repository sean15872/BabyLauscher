import assert from 'node:assert/strict';
import {analyzeAudio, comparePatterns, LABELS} from '../app/src/main/assets/web/signal.mjs';
function tone(freq, seconds=3, amplitude=.27, rate=12000) {
  const samples = new Float32Array(Math.round(seconds*rate));
  for (let i=0;i<samples.length;i++) samples[i] = amplitude*Math.sin(2*Math.PI*freq*i/rate)*(0.85+0.15*Math.sin(i/1000));
  return samples;
}
let silent=analyzeAudio(new Float32Array(12000*3), 12000);
assert.equal(silent.ok, false, 'Stille muss abgelehnt werden');
const a=analyzeAudio(tone(310),12000);
const b=analyzeAudio(tone(670),12000);
assert.equal(a.ok,true, 'Ton 310Hz messbar');
assert.equal(b.ok,true, 'Ton 670Hz messbar');
assert.equal(a.signature.length, 37);
assert.ok(a.metrics.pitchHz>250&&a.metrics.pitchHz<360, 'Tonhöhe 310Hz plausibel');
assert.ok(b.metrics.pitchHz>580&&b.metrics.pitchHz<750, 'Tonhöhe 670Hz plausibel');
assert.equal(comparePatterns(a.signature,[],6).ready,false,'Ohne Beispiele keine Klassifikation');
const train=[...Array(3)].map(()=>({signature:a.signature,label:'hunger',ageWeeks:6})).concat([...Array(3)].map(()=>({signature:b.signature,label:'muede',ageWeeks:6})));
const match=comparePatterns(a.signature,train,6);
assert.equal(match.ready,true);
assert.equal(match.suggest,'hunger','Ähnliches Signal nach Training wiedererkennen');
assert.equal(LABELS.length,6);
console.log('✓ Signaltest: Stille, 310Hz, 670Hz, Signatur, Trainingssperre, Ähnlichkeitsvergleich');
