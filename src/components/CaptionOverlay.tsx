import React from 'react';
import { Message, AIEmotion } from '../types';
import { ShieldCheck, Sparkles } from 'lucide-react';
import { getEmotionEmoji } from '../services/privacyGuard';

interface CaptionOverlayProps {
  visible: boolean;
  latestMessage: Message | null;
  emotion: AIEmotion;
  isListening: boolean;
  isSpeaking: boolean;
  onDismiss: () => void;
}

export const CaptionOverlay: React.FC<CaptionOverlayProps> = ({
  visible,
  latestMessage,
  emotion,
  isListening,
  isSpeaking,
  onDismiss
}) => {
  if (!visible || !latestMessage) return null;

  const getRoleIcon = (role: Message['role']) => {
    switch (role) {
      case 'you':
        return '🧑';
      case 'saara':
        return '✨';
      case 'tool':
        return '⚙️';
      default:
        return 'ℹ️';
    }
  };

  return (
    <div className="fixed top-4 left-1/2 -translate-x-1/2 z-40 max-w-xl w-[92%] pointer-events-none animate-in fade-in slide-in-from-top-3 duration-300">
      <div className="flex flex-col items-center">
        {/* AI Emotion Badge Displayed ABOVE floating text as requested */}
        <div className="mb-1 px-3 py-1 rounded-full bg-slate-900/95 border border-indigo-500/50 shadow-lg text-[11px] font-semibold text-indigo-300 flex items-center gap-1.5 pointer-events-auto backdrop-blur-md animate-bounce">
          <span className="text-sm">
            {emotion === 'thinking' && '🤔'}
            {emotion === 'happy' && '😊'}
            {emotion === 'confused' && '😕'}
            {emotion === 'sad' && '😭'}
            {emotion === 'focused' && '⚡'}
            {emotion === 'protective' && '🛡️'}
            {emotion === 'neutral' && '✨'}
          </span>
          <span>{getEmotionEmoji(emotion)}</span>
        </div>

        {/* Floating subtitle bar */}
        <div className="w-full pointer-events-auto bg-slate-900/90 backdrop-blur-md border border-slate-700/80 shadow-2xl rounded-2xl px-4 py-2.5 flex items-center justify-between gap-3">
          <div className="flex items-center gap-2.5 overflow-hidden">
            <span className="text-base select-none shrink-0">{getRoleIcon(latestMessage.role)}</span>
            <div className="overflow-hidden">
              <p className="text-xs text-slate-200 truncate font-medium">
                {latestMessage.text}
              </p>
              <div className="flex items-center gap-2 text-[10px] text-slate-400">
                <span className="capitalize">{latestMessage.role}</span>
                <span>•</span>
                <span className="text-slate-400">{latestMessage.timestamp}</span>
              </div>
            </div>
          </div>

          <button
            onClick={onDismiss}
            className="text-slate-500 hover:text-slate-300 text-xs px-2 py-0.5 rounded"
          >
            ✕
          </button>
        </div>
      </div>
    </div>
  );
};
