import { describe, expect, it } from 'vitest'
import { speakerColorVar, speakerIndex, speakerLabel, withSpeakerNames } from './speakers'
import { errorCode, errorMessage } from './errors'

describe('speakers', () => {
  it('палитра из 8 цветов по кругу', () => {
    expect(speakerIndex('speaker_1')).toBe(1)
    expect(speakerIndex('speaker_8')).toBe(8)
    expect(speakerIndex('speaker_9')).toBe(1)
    expect(speakerColorVar('speaker_3')).toBe('var(--speaker-3)')
  })
  it('имя спикера или номер', () => {
    expect(speakerLabel('speaker_2', {})).toBe('Спикер 2')
    expect(speakerLabel('speaker_2', { speaker_2: 'Председатель' })).toBe('Председатель')
    expect(speakerLabel('speaker_2', { speaker_2: '  ' })).toBe('Спикер 2')
  })
  it('в итогах speaker_N заменяется на имя', () => {
    expect(
      withSpeakerNames('speaker_1 рассказала о вязании, а Speaker_2 спросил про бизнес.', {
        speaker_2: 'Лёша',
      }),
    ).toBe('Спикер 1 рассказала о вязании, а Лёша спросил про бизнес.')
  })
})

describe('errors', () => {
  it('известный код → русский текст', () => {
    expect(errorCode({ code: 'NO_PERMISSION' })).toBe('NO_PERMISSION')
    expect(errorMessage({ code: 'DECODE_FAILED' })).toContain('аудиофайл')
  })
  it('неизвестное → UNKNOWN', () => {
    expect(errorCode(new Error('x'))).toBe('UNKNOWN')
    expect(errorCode(null)).toBe('UNKNOWN')
    expect(errorCode({ code: 'WHATEVER' })).toBe('UNKNOWN')
  })
})
