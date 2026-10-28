import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

// Execute the exact bundled player script against deterministic browser/RTC doubles.
// This verifies event ordering and settings; it is not an authenticated media test.
const html = fs.readFileSync(new URL('../app/src/main/assets/stream.html', import.meta.url), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
let checks = 0;
function fixture(microphone = false) {
  const calls = { ready: 0, errors: [], answers: [], stats: [], ice: [], channels: [], mic: null, samples: 0, intervals: new Map(), timeouts: new Map(), videoListeners: new Map(), pageListeners: new Map(), dimensions: [], now: 1000 };
  const video = { videoWidth: 1280, videoHeight: 720, play: async () => {}, addEventListener(name, callback) { calls.videoListeners.set(name, callback); }, requestVideoFrameCallback() {} };
  calls.video = video;
  const microphoneTrack = { kind: 'audio', enabled: true, stop() { this.stopped = true; } };
  class RTC {
    constructor(config) { calls.config = config; calls.pc = this; this.connectionState = 'connected'; }
    createDataChannel(label, options) { const c = { label, options, readyState: 'open', bufferedAmount: 0, sent: [], send(data) { this.sent.push(Array.from(data)); } }; calls.channels.push(c); return c; }
    async setRemoteDescription(d) { calls.remote = d; }
    async setLocalDescription(d) { this.localDescription = d; }
    async createAnswer() { return { type: 'answer', sdp: 'v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=ice-ufrag:local\r\n' }; }
    async addIceCandidate(c) { calls.ice.push(c); }
    getTransceivers() { return calls.transceivers || []; }
    addTrack(t) { calls.mic = t; }
    async getStats() { calls.samples++; if (calls.statsError) throw calls.statsError; if (calls.pendingStats) return await calls.pendingStats; return calls.report || new Map([
      ['video', { id: 'ssrc1', type: 'inbound-rtp', kind: 'video', bytesReceived: 1000000, framesPerSecond: 30, frameWidth: 1280, frameHeight: 720, totalDecodeTime: .12, framesDecoded: 30, framesDropped: 2, codecId: 'codec' }],
      ['pair', { type: 'candidate-pair', nominated: true, state: 'succeeded', currentRoundTripTime: .027 }],
      ['codec', { mimeType: 'video/H264' }],
    ]); }
    close() { this.connectionState = 'closed'; }
  }
  const sandbox = {
    RTCPeerConnection: RTC,
    MediaStream: class { addTrack() {} },
    document: { getElementById: () => video },
    navigator: { mediaDevices: { getUserMedia: async () => ({ getAudioTracks: () => [microphoneTrack], getTracks: () => [microphoneTrack] }) } },
    setInterval: (callback, ms) => { const id = calls.intervals.size + 1; calls.intervals.set(id, ms); return id; },
    clearInterval: id => calls.intervals.delete(id),
    setTimeout: (callback, ms) => { const id = calls.timeouts.size + 1; calls.timeouts.set(id, callback); return id; },
    clearTimeout: id => calls.timeouts.delete(id),
    performance: { now: () => calls.now },
    atob: s => Buffer.from(s, 'base64').toString('binary'),
    BGFN: {
      ready: () => calls.ready++, firstFrame() {}, error: e => calls.errors.push(e), videoSize: (w, h) => calls.dimensions.push([w,h]),
      answer: s => calls.answers.push(s), candidate: s => calls.candidate = JSON.parse(s), stats: s => calls.stats.push(JSON.parse(s)),
    },
  };
  sandbox.window = { RTCPeerConnection: RTC, addEventListener(name, callback) { calls.pageListeners.set(name,callback); } };
  const context = vm.createContext(sandbox);
  vm.runInContext(script, context);
  return { calls, run: source => vm.runInContext(source, context), microphone };
}

const f = fixture();
await f.run('init({iceServers:[],microphone:false,statistics:true})');
assert.equal(f.calls.ready, 1); checks++;
assert.equal(f.calls.config.bundlePolicy, 'max-bundle'); checks++;
await f.run('ice({candidate:"candidate:fixture",sdpMid:"0",sdpMLineIndex:0})');
assert.equal(f.calls.ice.length, 0); checks++;
await f.run('offer("v=0\\r\\n",8000)');
assert.equal(f.calls.ice.length, 1); checks++;
assert.deepEqual(f.calls.channels.map(c => c.label), ['input_channel_v1', 'input_channel_partially_reliable', 'stats_channel']); checks++;
assert.equal(f.calls.channels[1].options.maxPacketLifeTime, 30); checks++;
assert.match(f.calls.answers[0], /b=AS:8000/); checks++;
f.run('input("AgAAAA==")');
assert.deepEqual(f.calls.channels[0].sent[0], [2, 0, 0, 0]); checks++;
f.calls.channels[0].bufferedAmount = 40000;
f.run('input("AgAAAA==")');
assert.equal(f.calls.channels[0].sent.length, 1); checks++;
f.calls.channels[0].bufferedAmount = 0;
f.run('drainInputs()');
assert.equal(f.calls.channels[0].sent.length, 2); checks++;
await f.run('sample()');
assert.equal(f.calls.stats[0].ping, 27); checks++;
assert.equal(f.calls.stats[0].decodeMs, -1); checks++;
assert.equal(f.calls.stats[0].width, 1280); checks++;
assert.equal(f.calls.stats[0].codec, 'video/H264'); checks++;

const m = fixture(true);
await m.run('init({iceServers:[],microphone:true})');
const transceiver = { mid: '3', receiver: { track: { kind: 'audio' } }, direction: 'sendonly', sender: { replaceTrack: async t => { m.calls.mic = t; } } };
m.calls.transceivers = [transceiver];
await m.run('offer("v=0\\r\\n",5000)');
assert.equal(transceiver.direction, 'sendonly'); checks++;
assert.equal(m.calls.mic.kind, 'audio'); checks++;
m.run('mute(true)');
assert.equal(f.calls.errors.length + m.calls.errors.length, 0); checks++;
f.run('statistics(false)');
const samplesBefore = f.calls.samples;
await f.run('sample()');
assert.equal(f.calls.samples, samplesBefore); checks++;
assert.equal(f.calls.intervals.size, 0); checks++;
f.run('statistics(true)');
assert.equal(Array.from(f.calls.intervals.values())[0], 2000); checks++;
await f.run('sample()');
assert.equal(f.calls.samples, samplesBefore + 1); checks++;

const q = fixture();
await q.run('init({iceServers:[],microphone:false,statistics:false})');
await q.run('sample()');
assert.equal(q.calls.samples, 0); checks++;
q.run('input("AwAAAA=="); input("BAAAAA==")');
await q.run('offer("v=0\\r\\n",4000)');
q.run('drainInputs()');
assert.deepEqual(q.calls.channels[0].sent, [[3,0,0,0], [4,0,0,0]]); checks++;

const batch = fixture();
await batch.run('init({iceServers:[],microphone:false,statistics:false})');
batch.run('inputs(["AwAAAA==","BAAAAA==","IhcAAADDoQ=="])');
assert.equal(batch.calls.timeouts.size, 1); checks++;
await batch.run('offer("v=0\\r\\n",8000)');
batch.run('drainInputs()');
assert.deepEqual(batch.calls.channels[0].sent, [[3,0,0,0],[4,0,0,0],[34,23,0,0,0,195,161]]); checks++;
assert.equal(batch.calls.timeouts.size, 0); checks++;
batch.calls.channels[0].bufferedAmount = 40000;
batch.run('inputs(["AwAAAA==","BAAAAA=="])');
assert.equal(batch.calls.channels[0].sent.length, 3); checks++;
batch.calls.channels[0].bufferedAmount = 0;
batch.run('drainInputs()');
assert.deepEqual(batch.calls.channels[0].sent.slice(3), [[3,0,0,0],[4,0,0,0]]); checks++;
assert.equal(batch.calls.errors.length, 0); checks++;

const bounded = fixture();
await bounded.run('init({iceServers:[],microphone:false,statistics:false})');
bounded.run('inputs(Array(129).fill("AgAAAA=="))');
assert.equal(bounded.calls.errors.length, 1); checks++;
await bounded.run('offer("v=0\\r\\n",8000)');
bounded.run('drainInputs()');
assert.equal(bounded.calls.channels[0].sent.length, 128); checks++;
assert.equal(bounded.calls.timeouts.size, 0); checks++;

f.run('audio(false)'); assert.equal(f.calls.video.muted, true); checks++;
f.run('audio(true)'); assert.equal(f.calls.video.muted, false); checks++;
m.run('mute(true)'); assert.equal(m.calls.mic.enabled, false); checks++;
m.run('mute(false)'); assert.equal(m.calls.mic.enabled, true); checks++;
m.run('audio(false)'); assert.equal(m.calls.mic.enabled, true); checks++;
m.calls.videoListeners.get('resize')(); assert.deepEqual(m.calls.dimensions, [[1280,720]]); checks++;
m.calls.video.videoWidth = 1920; m.calls.video.videoHeight = 1080;
m.calls.videoListeners.get('resize')(); assert.deepEqual(m.calls.dimensions[1], [1920,1080]); checks++;
m.calls.pageListeners.get('pagehide')(); assert.equal(m.calls.mic.stopped, true); checks++;
assert.equal(m.calls.pc.connectionState, 'closed'); checks++;
assert.equal(m.calls.intervals.size + m.calls.timeouts.size, 0); checks++;


// Recent decode/bitrate samples, counter resets and optional HUD failures.
const stats = fixture();
await stats.run('init({iceServers:[],microphone:false,statistics:true})');
const report = (video = {}, pair = {}) => new Map([
  ['video', { id: 'ssrc1', type: 'inbound-rtp', kind: 'video', bytesReceived: 1000000, framesDecoded: 1000, totalDecodeTime: 4, frameWidth: 1280, frameHeight: 720, ...video }],
  ['pair', { type: 'candidate-pair', nominated: true, state: 'succeeded', currentRoundTripTime: .023, ...pair }],
]);
stats.calls.report = report(); await stats.run('sample()');
assert.equal(stats.calls.stats.at(-1).mbps, -1); checks++;
stats.calls.now = 3000;
stats.calls.report = report({ bytesReceived: 3000000, framesDecoded: 1120, totalDecodeTime: 4.96 });
await stats.run('sample()');
assert.equal(stats.calls.stats.at(-1).mbps, 8); checks++;
assert.equal(stats.calls.stats.at(-1).fps, 60); checks++;
assert.ok(Math.abs(stats.calls.stats.at(-1).decodeMs - 8) < .0001); checks++;
await stats.run('sample()');
assert.equal(stats.calls.stats.at(-1).mbps, -1); checks++;
assert.equal(stats.calls.stats.at(-1).decodeMs, -1); checks++;
stats.calls.now = 5000;
stats.calls.report = report({ bytesReceived: 20, framesDecoded: 2, totalDecodeTime: .01 });
await stats.run('sample()');
assert.equal(stats.calls.stats.at(-1).mbps, -1); checks++;
assert.equal(stats.calls.stats.at(-1).fps, -1); checks++;
stats.calls.now = 7000;
stats.calls.report = report({ id: 'new-ssrc', bytesReceived: 9000000, framesDecoded: 4000, totalDecodeTime: 50 });
await stats.run('sample()');
assert.equal(stats.calls.stats.at(-1).decodeMs, -1); checks++;
stats.calls.now = 9000;
stats.calls.report = report({ bytesReceived: Infinity, framesPerSecond: NaN, framesDecoded: -1, totalDecodeTime: Infinity }, { currentRoundTripTime: NaN });
await stats.run('sample()');
assert.equal(stats.calls.stats.at(-1).ping, -1); checks++;
assert.equal(stats.calls.stats.at(-1).fps, -1); checks++;
assert.ok(Object.values(stats.calls.stats.at(-1)).every(v => typeof v !== 'number' || Number.isFinite(v))); checks++;
stats.calls.statsError = Error('getStats temporarily unavailable');
await stats.run('sample()');
assert.equal(stats.calls.errors.length, 0); checks++;
stats.calls.statsError = null;
stats.calls.report = report();
await stats.run('sample()');
assert.equal(stats.calls.samples, 8); checks++;
let releaseStats;
stats.calls.pendingStats = new Promise(resolve => { releaseStats = resolve; });
const pendingSample = stats.run('sample()');
const countBeforeDisable = stats.calls.stats.length;
stats.run('statistics(false); statistics(true)');
releaseStats(report()); await pendingSample;
assert.equal(stats.calls.stats.length, countBeforeDisable); checks++;
stats.calls.pendingStats = null;
await stats.run('sample()');
assert.equal(stats.calls.stats.length, countBeforeDisable + 1); checks++;
stats.calls.pageListeners.get('pagehide')();
const beforeHide = stats.calls.samples;
await stats.run('sample()');
assert.equal(stats.calls.samples, beforeHide); checks++;

console.log(`${checks} player checks passed. Real Android/WebView and live GFN validation remain separate.`);
