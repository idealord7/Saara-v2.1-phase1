import React from 'react';
import { Mic, MicOff, Sparkles, Volume2 } from 'lucide-react';
import { AIEmotion } from '../types';

interface FloatingBubbleProps {
  visible: boolean;
  emotion: AIEmotion;
  isListening: boolean;
  isSpeaking: boolean;
  onToggleMic: () => void;
  onClick: () => void;
}

export const FloatingBubble: React.FC<FloatingBubbleProps> = ({
  visible,
  emotion,
  isListening,
  isSpeaking,
  onToggleMic,
  onClick
}) => {
  if (!visible) return null;

  const getEmotionIcon = () => {
    switch (emotion) {
      case 'thinking':
        return '🤔';
      case 'happy':
        return '😊';
      case 'confused':
        return '😕';
      case 'sad':
        return '😭';
      case 'focused':
        return '⚡';
      case 'protective':
        return '🛡️';
      default:
        return '✨';
    }
  };

  return (
    <div className="fixed bottom-6 right-6 z-40 flex flex-col items-center gap-1.5 select-none">
      {/* Emotion Pill Above Bubble */}
      <div className="px-2 py-0.5 rounded-full bg-slate-900 border border-indigo-500/50 text-[10px] text-indigo-300 font-bold shadow-md animate-pulse">
        {getEmotionIcon()}
      </div>

      <div className="group relative">
        {/* Glow halo */}
        <div
          className={`absolute -inset-1 rounded-full blur-md opacity-75 transition-all duration-500 ${
            isSpeaking
              ? 'bg-gradient-to-r from-pink-500 to-purple-500 animate-pulse'
              : isListening
              ? 'bg-gradient-to-r from-emerald-500 to-cyan-500 animate-pulse'
              : 'bg-indigo-600/40'
          }`}
        />

        {/* Main round bubble button */}
        <button
          onClick={onClick}
          className="relative w-14 h-14 rounded-full bg-slate-900 border-2 border-indigo-400/50 shadow-2xl flex items-center justify-center text-white hover:scale-105 active:scale-95 transition"
          title="Saara Quick Assistant Bubble"
        >
          {isSpeaking ? (
            <Volume2 className="w-6 h-6 text-purple-400 animate-bounce" />
          ) : isListening ? (
            <Mic className="w-6 h-6 text-emerald-400 animate-pulse" />
          ) : (
            <Sparkles className="w-6 h-6 text-indigo-400" />
          )}
        </button>

        {/* Floating mini-mic badge button */}
        <button
          onClick={(e) => {
            e.stopPropagation();
            onToggleMic();
          }}
          className={`absolute -bottom-1 -right-1 w-6 h-6 rounded-full flex items-center justify-center shadow-md text-[10px] transition ${
            isListening
              ? 'bg-emerald-500 text-slate-950 hover:bg-emerald-400'
              : 'bg-slate-800 text-slate-300 hover:bg-slate-700 border border-slate-600'
          }`}
          title={isListening ? 'Mute Mic' : 'Start Mic'}
        >
          {isListening ? <Mic className="w-3.5 h-3.5" /> : <MicOff className="w-3.5 h-3.5" />}
        </button>
      </div>
    </div>
  );
};
