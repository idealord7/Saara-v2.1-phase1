/**
 * Web Speech Recognition & Synthesis for Saara Assistant
 * Supports Wake Word detection, Voice Match analysis, and Hotword triggers.
 */

interface IWindow extends Window {
  webkitSpeechRecognition?: any;
  SpeechRecognition?: any;
}

let recognitionInstance: any = null;
let audioContext: AudioContext | null = null;

function getAudioContext(): AudioContext {
  if (!audioContext) {
    const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
    audioContext = new AudioCtx();
  }
  if (audioContext.state === 'suspended') {
    audioContext.resume();
  }
  return audioContext;
}

export function playChime(type: 'start' | 'success' | 'alert' | 'click' | 'incoming_call' = 'click') {
  try {
    const ctx = getAudioContext();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();

    osc.connect(gain);
    gain.connect(ctx.destination);

    const now = ctx.currentTime;
    if (type === 'start') {
      osc.frequency.setValueAtTime(440, now);
      osc.frequency.exponentialRampToValueAtTime(880, now + 0.15);
      gain.gain.setValueAtTime(0.12, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.25);
      osc.start(now);
      osc.stop(now + 0.25);
    } else if (type === 'success') {
      osc.frequency.setValueAtTime(587.33, now); // D5
      osc.frequency.setValueAtTime(880, now + 0.1); // A5
      gain.gain.setValueAtTime(0.1, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.3);
      osc.start(now);
      osc.stop(now + 0.3);
    } else if (type === 'incoming_call') {
      // Ringtone chime
      osc.frequency.setValueAtTime(853, now);
      osc.frequency.setValueAtTime(960, now + 0.2);
      gain.gain.setValueAtTime(0.18, now);
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.8);
      osc.start(now);
      osc.stop(now + 0.8);
    } else if (type === 'alert') {
      osc.frequency.setValueAtTime(320, now);
      osc.frequency.setValueAtTime(240, now + 0.12);
      gain.gain.setValueAtTime(0.15, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.3);
      osc.start(now);
      osc.stop(now + 0.3);
    } else {
      osc.frequency.setValueAtTime(800, now);
      gain.gain.setValueAtTime(0.05, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.05);
      osc.start(now);
      osc.stop(now + 0.05);
    }
  } catch (e) {
    // AudioContext blocked until user gesture
  }
}

/**
 * Filter speech based on assistant name wake word.
 * If wakeWordFilter is enabled, returns { matched: boolean, command: string }.
 * E.g., "Saara make a call" -> matched: true, command: "make a call"
 */
export function filterWakeWord(
  transcript: string,
  assistantName: string = 'Saara',
  wakeWordEnabled: boolean = false
): { matched: boolean; command: string } {
  if (!wakeWordEnabled) {
    return { matched: true, command: transcript.trim() };
  }

  const name = assistantName.trim().toLowerCase();
  const text = transcript.trim().toLowerCase();

  const regex = new RegExp(`^.*?\\b${name}\\b[,:]?\\s*(.*)$`, 'i');
  const match = transcript.match(regex);

  if (match) {
    const cmd = (match[1] || '').trim();
    return {
      matched: true,
      command: cmd || `Hello ${assistantName}`
    };
  }

  return { matched: false, command: '' };
}

/**
 * Checks if a hotword phrase was spoken (e.g. "Hey Saara", "OK Saara")
 */
export function checkHotwordSpoken(
  transcript: string,
  hotwordPhrase: string = 'Hey Saara',
  assistantName: string = 'Saara'
): boolean {
  const cleanTranscript = transcript.toLowerCase().trim();
  const cleanPhrase = hotwordPhrase.toLowerCase().trim();
  const cleanName = assistantName.toLowerCase().trim();

  return (
    cleanTranscript.includes(cleanPhrase) ||
    cleanTranscript.includes(`hey ${cleanName}`) ||
    cleanTranscript.includes(`ok ${cleanName}`) ||
    cleanTranscript.includes(`okay ${cleanName}`)
  );
}

export function startSpeechRecognition({
  onResult,
  onError,
  onEnd,
  language = 'en-US',
  continuous = true
}: {
  onResult: (transcript: string, isFinal: boolean) => void;
  onError: (error: string) => void;
  onEnd: () => void;
  language?: string;
  continuous?: boolean;
}): boolean {
  const win = window as IWindow;
  const SpeechRec = win.SpeechRecognition || win.webkitSpeechRecognition;

  if (!SpeechRec) {
    onError('Speech recognition is not supported in this browser. You can type commands directly.');
    return false;
  }

  try {
    if (recognitionInstance) {
      try {
        recognitionInstance.stop();
      } catch (_) {}
    }

    const rec = new SpeechRec();
    rec.continuous = continuous;
    rec.interimResults = true;
    rec.lang = language;

    rec.onresult = (event: any) => {
      let interim = '';
      let final = '';

      for (let i = event.resultIndex; i < event.results.length; ++i) {
        if (event.results[i].isFinal) {
          final += event.results[i][0].transcript;
        } else {
          interim += event.results[i][0].transcript;
        }
      }

      if (final.trim()) {
        onResult(final.trim(), true);
      } else if (interim.trim()) {
        onResult(interim.trim(), false);
      }
    };

    rec.onerror = (event: any) => {
      if (event.error !== 'no-speech') {
        onError(event.error || 'Speech recognition error');
      }
    };

    rec.onend = () => {
      onEnd();
    };

    rec.start();
    recognitionInstance = rec;
    return true;
  } catch (err: any) {
    onError(err?.message || 'Could not start speech recognition');
    return false;
  }
}

export function stopSpeechRecognition() {
  if (recognitionInstance) {
    try {
      recognitionInstance.stop();
    } catch (_) {}
    recognitionInstance = null;
  }
}

export function speakText(text: string, onDone?: () => void, lang = 'en-US') {
  if (!('speechSynthesis' in window)) {
    onDone?.();
    return;
  }

  const clean = text
    .replace(/\*+/g, '')
    .replace(/\[.*?\]/g, '')
    .replace(/#+/g, '')
    .trim();

  if (!clean) {
    onDone?.();
    return;
  }

  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(clean);
  utterance.lang = lang;
  utterance.rate = 1.05;
  utterance.pitch = 1.15; // Warm female voice tone

  const voices = window.speechSynthesis.getVoices();
  const femaleVoice = voices.find(
    v =>
      (v.name.includes('Female') ||
        v.name.includes('Samantha') ||
        v.name.includes('Zira') ||
        v.name.includes('Victoria') ||
        v.name.includes('Google UK English Female') ||
        v.name.includes('Natural')) &&
      v.lang.startsWith('en')
  );

  if (femaleVoice) {
    utterance.voice = femaleVoice;
  }

  utterance.onend = () => {
    onDone?.();
  };

  utterance.onerror = () => {
    onDone?.();
  };

  window.speechSynthesis.speak(utterance);
}

export function stopSpeaking() {
  if ('speechSynthesis' in window) {
    window.speechSynthesis.cancel();
  }
}
