export const SPEAKER_COLORS = 8

/** speaker_3 → 3; цвет берётся из палитры --speaker-N по кругу. */
export function speakerIndex(speakerId: string): number {
  const n = Number(/(\d+)$/.exec(speakerId)?.[1] ?? 1)
  return ((Math.max(1, n) - 1) % SPEAKER_COLORS) + 1
}

export function speakerColorVar(speakerId: string): string {
  return `var(--speaker-${speakerIndex(speakerId)})`
}

export function speakerLabel(speakerId: string, names: Record<string, string>): string {
  const name = names[speakerId]?.trim()
  if (name) return name
  return `Спикер ${speakerId.replace(/^speaker_/, '')}`
}
