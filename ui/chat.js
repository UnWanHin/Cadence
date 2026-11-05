// Chat message merging and polling for the local-first LAN room.
//
// Two clients share messages only through the room service. A message can
// reach us more than once (optimistic echo, poll response, WebSocket push),
// and the room service assigns the canonical messageId. Dedupe on message
// content so one logical message renders exactly once.

export const CHAT_LIMIT = 100;

export function signature(message) {
  return `${message.profileId || ''}|${Number(message.sentAt) || 0}|${message.text || ''}`;
}

export function mergeMessages(current, incoming, limit = CHAT_LIMIT) {
  const bySignature = new Map();
  for (const message of current || []) bySignature.set(signature(message), message);
  for (const message of incoming || []) {
    if (!message) continue;
    const key = signature(message);
    const existing = bySignature.get(key);
    // Prefer the copy that carries a server-assigned messageId.
    if (existing && existing.messageId && !message.messageId) continue;
    bySignature.set(key, {
      ...existing,
      ...message,
      messageId: message.messageId || existing?.messageId || key,
    });
  }
  return [...bySignature.values()]
    .sort((a, b) => Number(a.sentAt) - Number(b.sentAt))
    .slice(-limit);
}

export function createChatStore(request) {
  let messages = [];

  function apply(incoming) {
    const next = mergeMessages(messages, incoming);
    const changed = JSON.stringify(next) !== JSON.stringify(messages);
    messages = next;
    return changed;
  }

  return {
    get messages() { return messages; },
    merge: apply,
    async pull() {
      const payload = await request('/chat');
      return apply(payload.messages);
    },
    async send(profile, text) {
      const response = await request('/chat', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          username: profile.username,
          profileId: profile.profileId,
          text,
          sentAt: Date.now(),
        }),
      });
      apply([response]);
      return response;
    },
  };
}
