class CaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super(); this.running = true; this.chunks = []; this.length = 0;
    this.port.onmessage = event => { if (event.data === 'stop') { this.flush(); this.running = false; } };
  }
  flush() {
    if (!this.length) return;
    const output = new Float32Array(this.length); let offset = 0;
    for (const a of this.chunks) { output.set(a, offset); offset += a.length; }
    this.port.postMessage(output, [output.buffer]);
    this.chunks.length = 0; this.length = 0;
  }
  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (this.running && channel) {
      this.chunks.push(new Float32Array(channel)); this.length += channel.length;
      if (this.length >= 4096) this.flush();
    }
    return true;
  }
}
registerProcessor('baby-capture', CaptureProcessor);
