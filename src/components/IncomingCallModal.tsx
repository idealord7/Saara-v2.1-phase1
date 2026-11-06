import React, { useEffect, useState } from 'react';
import { Phone, PhoneOff, PhoneCall, Volume2, Mic, Check, X } from 'lucide-react';
import { IncomingCallState } from '../types';
import { startSpeechRecognition, stopSpeechRecognition, speakText, playChime } from '../services/speechService';

interface IncomingCallModalProps {
  callState: IncomingCallState;
  onAnswer: () => void;
  onDecline: () => void;
  assistantName?: string;
  autoSpeak?: boolean;
}

export const IncomingCallModal: React.FC<IncomingCallModalProps> = ({
  callState,
  onAnswer,
  onDecline,
  assistantName = 'Saara',
  autoSpeak = true
}) => {
  const [voiceAnswerListening, setVoiceAnswerListening] = useState(true);
  const [heardSpeech, setHeardSpeech] = useState<string>('');

  useEffect(() => {
    if (!callState.active || callState.status !== 'ringing') return;

    playChime('incoming_call');

    // Assistant speech prompt
    if (autoSpeak) {
      speakText(
        `Incoming call from ${callState.callerName}. Do you want to answer the incoming call? Say yes or no.`,
        () => {
          // Listen for voice response
          startSpeechRecognition({
            onResult: (transcript, isFinal) => {
              const lower = transcript.toLowerCase().trim();
              setHeardSpeech(transcript);
              if (lower.includes('yes') || lower.includes('answer') || lower.includes('pick') || lower.includes('haan')) {
                stopSpeechRecognition();
                onAnswer();
              } else if (lower.includes('no') || lower.includes('decline') || lower.includes('reject') || lower.includes('cut') || lower.includes('nahi')) {
                stopSpeechRecognition();
                onDecline();
              }
            },
            onError: () => {},
            onEnd: () => {},
            continuous: true
          });
        }
      );
    }

    return () => {
      stopSpeechRecognition();
    };
  }, [callState.active, callState.status]);

  if (!callState.active) return null;

  return (
    <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4 animate-in fade-in zoom-in-95 duration-200">
      <div className="bg-slate-900 border-2 border-emerald-500/80 w-full max-w-sm rounded-[36px] shadow-2xl p-6 text-center flex flex-col items-center">
        {/* Ringing pulse avatar */}
        <div className="relative my-4">
          <div className="w-24 h-24 rounded-full bg-emerald-500/20 border-2 border-emerald-400 animate-ping absolute inset-0" />
          <div className="w-24 h-24 rounded-full bg-slate-950 border-2 border-emerald-400 flex items-center justify-center relative shadow-xl shadow-emerald-500/30">
            <PhoneCall className="w-10 h-10 text-emerald-400 animate-bounce" />
          </div>
        </div>

        <h3 className="text-xl font-bold text-white mt-2">{callState.callerName}</h3>
        <p className="text-xs text-slate-400 font-mono mt-0.5">{callState.callerNumber}</p>
        <span className="text-[10px] font-semibold uppercase tracking-wider text-emerald-400 mt-2 px-3 py-1 rounded-full bg-emerald-500/10 border border-emerald-500/30">
          Incoming Cellular Call
        </span>

        {/* Saara Voice Prompt Banner */}
        <div className="my-5 p-3 rounded-2xl bg-indigo-950/60 border border-indigo-500/40 text-xs text-indigo-200 w-full">
          <div className="flex items-center justify-center gap-1.5 font-semibold text-indigo-300 mb-1">
            <Mic className="w-4 h-4 text-emerald-400 animate-pulse" />
            <span>{assistantName} is listening for your voice:</span>
          </div>
          <p className="text-[11px] text-slate-300">
            Say <strong className="text-emerald-400">"Yes"</strong> to answer or <strong className="text-rose-400">"No"</strong> to decline.
          </p>
          {heardSpeech && (
            <p className="text-[10px] text-amber-300 mt-1 italic font-mono">
              Heard: "{heardSpeech}"
            </p>
          )}
        </div>

        {/* Answer / Decline Buttons */}
        <div className="flex items-center justify-around w-full mt-2">
          {/* Decline */}
          <button
            onClick={onDecline}
            className="flex flex-col items-center gap-1.5 group"
          >
            <div className="w-14 h-14 rounded-full bg-rose-600 hover:bg-rose-500 text-white flex items-center justify-center shadow-lg shadow-rose-600/30 group-hover:scale-105 transition">
              <PhoneOff className="w-6 h-6" />
            </div>
            <span className="text-xs font-semibold text-rose-400">Decline (No)</span>
          </button>

          {/* Answer */}
          <button
            onClick={onAnswer}
            className="flex flex-col items-center gap-1.5 group"
          >
            <div className="w-14 h-14 rounded-full bg-emerald-600 hover:bg-emerald-500 text-white flex items-center justify-center shadow-lg shadow-emerald-600/30 group-hover:scale-105 transition">
              <Phone className="w-6 h-6" />
            </div>
            <span className="text-xs font-semibold text-emerald-400">Answer (Yes)</span>
          </button>
        </div>
      </div>
    </div>
  );
};
