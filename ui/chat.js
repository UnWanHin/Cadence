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

/**
 * Returns copies of [messages] whose usernames reflect the current profile
 * names. A person can rename themselves, so the newest name seen in the room
 * wins; the local profile always uses its name from this page immediately.
 */
export function withDisplayNames(messages, events = [], profile = {}) {
  const names = new Map();
  const latestAt = new Map();
  const confirmedNames = new Map();
  const confirmedAt = new Map();
  const offer = (profileId, username, at) => {
    const id = String(profileId || '').trim();
    const name = String(username || '').trim();
    if (!id || !name) return;
    const previous = latestAt.get(id);
    if (previous === undefined || at >= previous) {
      latestAt.set(id, at);
      names.set(id, name);
    }
  };
  for (const event of events || []) {
    offer(event.profileId || event.deviceId, event.username || event.n, Number(event.occurredAt ?? event.t) || 0);
  }
  for (const message of messages || []) {
    const renamedAt = Number(message.nameUpdatedAt) || 0;
    offer(message.profileId, message.username,
      Math.max(Number(message.sentAt) || 0, renamedAt));
    // The room rewrote this message after the fact, so this name is current
    // even when another device's clock makes an old event look newer.
    if (renamedAt > 0 && message.profileId && message.username) {
      const previous = confirmedAt.get(message.profileId);
      if (previous === undefined || renamedAt >= previous) {
        confirmedAt.set(message.profileId, renamedAt);
        confirmedNames.set(message.profileId, String(message.username).trim());
      }
    }
  }
  for (const [id, name] of confirmedNames) names.set(id, name);
  if (profile.profileId && profile.username) names.set(profile.profileId, profile.username);
  return messages.map((message) => {
    const username = names.get(message.profileId);
    return username && username !== message.username ? { ...message, username } : message;
  });
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
