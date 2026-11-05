# Saara (self-contained Android app, no Termux needed)
Build via GitHub Actions (.github/workflows/build.yml) -> artifact Saara-apk.
Features are switched on/off inside the app; permissions have Grant buttons.

## Changelog (fix round 1)
- WhatsApp / WhatsApp Business: fuzzy app lookup (incl. Hindi names), reply button also detected in the Wear extender, empty names list = reply to all messaging apps, reply-loop guard.
- Multi-step tasks: agent-style system prompt; every ui_action / open_app returns the new screen; new ops: swipe, scroll_up, wait, enter, long_press, recents, notifications, clear_text; accessibility now includes "not important" views.
- Audio now plays on the loudspeaker (USAGE_MEDIA) instead of the earpiece.
- `type` appends to the existing text (replace=true to overwrite), with clipboard fallback.
- Camera: device_control take_photo / open_camera + ui_action shutter / flip_camera.
- Calls: device_control answer_call / end_call / reject_call (TelecomManager -> notification button -> on-screen button). New permission ANSWER_PHONE_CALLS (tap Grant again).

## Changelog (v2.0 — new look + new features)
- New logo (launcher icon, app header, floating bubble) and Gemini-style dark UI with bottom tabs: Chat, Learning, Tasks, Knowledge, Settings.
- Chat history is saved in SQLite; scroll to the top of the chat to load older messages.
- Language priority chips (Auto / Bengali / Hindi / English / Hinglish). Bengali, Hindi, English are always first priority.
- Live camera sharing and live screen sharing (MediaProjection) streamed to Gemini ~1 frame/sec, buttons in Chat.
- Live caption overlay (transparent, click-through) showing what Saara hears / says / does.
- Learning tab: what Saara remembers (grouped, deletable), usage summary, teach-her box. New tools: remember(category), recall.
- Shop ledger tab + tools shop_record / shop_report: purchase price, sale price, cancellations, margin per item.
- Scheduler (cron-style): schedule_task / list_tasks / cancel_task, survives reboot.
- Settings: model picker + "fetch live models for my key", voice picker, clear chat history.
