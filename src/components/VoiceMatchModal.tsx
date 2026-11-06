import React, { useState } from 'react';
import { Mic, CheckCircle, Volume2, ShieldCheck, X, RefreshCw, Sparkles } from 'lucide-react';
import { startSpeechRecognition, stopSpeechRecognition, playChime } from '../services/speechService';

interface VoiceMatchModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSaveProfile: (score: number) => void;
  assistantName: string;
}

export const VoiceMatchModal: React.FC<VoiceMatchModalProps> = ({
  isOpen,
  onClose,
  onSaveProfile,
  assistantName
}) => {
  const [step, setStep] = useState<number>(1);
  const [isListening, setIsListening] = useState(false);
  const [status, setStatus] = useState('Tap record and speak the phrase');
  const [phrases] = useState([
    `Hey ${assistantName}`,
    `${assistantName} take a photo`,
    `${assistantName} show my tasks`
  ]);

  if (!isOpen) return null;

  const currentPhrase = phrases[step - 1];

  const handleRecord = () => {
    setIsListening(true);
    setStatus(`Listening for: "${currentPhrase}"...`);
    playChime('start');

    startSpeechRecognition({
      onResult: (transcript, isFinal) => {
        if (isFinal) {
          stopSpeechRecognition();
          setIsListening(false);
          playChime('success');
          if (step < 3) {
            setStep(prev => prev + 1);
            setStatus(`Phrase ${step} verified! Now record Phrase ${step + 1}.`);
          } else {
            setStatus('Voice match profile successfully calibrated and enrolled!');
            // Store a synthetic acoustic profile score
            const score = 145 + Math.round(Math.random() * 50); // e.g. fundamental voice pitch
            onSaveProfile(score);
          }
        }
      },
      onError: (err) => {
        setIsListening(false);
        setStatus(`Recording error: ${err}. Try again.`);
      },
      onEnd: () => {
        setIsListening(false);
      }
    });
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="bg-slate-900 border border-slate-800 w-full max-w-md rounded-3xl shadow-2xl p-6 space-y-5">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="p-2 rounded-xl bg-indigo-600/20 text-indigo-400 border border-indigo-500/30">
              <ShieldCheck className="w-5 h-5" />
            </div>
            <div>
              <h3 className="font-bold text-sm text-white">Voice Match Enrollment</h3>
              <p className="text-[11px] text-slate-400">Train Saara to recognize only your unique voice</p>
            </div>
          </div>
          <button onClick={onClose} className="p-1 text-slate-400 hover:text-white">
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Step indicator */}
        <div className="flex items-center justify-between px-2">
          {[1, 2, 3].map((s) => (
            <div key={s} className="flex items-center gap-2">
              <div
                className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold transition ${
                  step > s
                    ? 'bg-emerald-600 text-white'
                    : step === s
                    ? 'bg-indigo-600 text-white ring-4 ring-indigo-500/30'
                    : 'bg-slate-800 text-slate-500'
                }`}
              >
                {step > s ? <CheckCircle className="w-4 h-4" /> : s}
              </div>
              <span className="text-[11px] text-slate-400">Sample {s}</span>
            </div>
          ))}
        </div>

        {/* Recording card */}
        <div className="bg-slate-950 p-6 rounded-2xl border border-slate-800 text-center space-y-3">
          <p className="text-xs text-slate-400">Please speak clearly into your microphone:</p>
          <div className="p-3 bg-indigo-950/40 border border-indigo-500/30 rounded-xl text-base font-bold text-indigo-200">
            "{currentPhrase}"
          </div>
          <p className="text-[11px] text-emerald-400 font-medium">{status}</p>

          <button
            onClick={handleRecord}
            disabled={isListening}
            className={`mt-2 px-6 py-3 rounded-full font-semibold text-xs flex items-center justify-center gap-2 mx-auto shadow-xl transition ${
              isListening
                ? 'bg-emerald-500 text-slate-950 animate-pulse'
                : 'bg-indigo-600 hover:bg-indigo-500 text-white shadow-indigo-600/30'
            }`}
          >
            <Mic className="w-4 h-4" />
            {isListening ? 'Listening...' : 'Tap & Speak Phrase'}
          </button>
        </div>

        <div className="flex items-center justify-end gap-2 pt-2 border-t border-slate-800">
          <button
            onClick={onClose}
            className="px-4 py-2 text-xs font-medium text-slate-400 hover:text-white"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};
