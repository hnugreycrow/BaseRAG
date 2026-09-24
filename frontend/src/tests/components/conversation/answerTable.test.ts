import { describe, expect, it } from 'vitest'

import { answerTable } from '../../../components/conversation/answerTable'

describe('answer tables', () => {
  it('supports optional outer pipes, alignment, escaped pipes and incomplete streamed rows', () => {
    const table = answerTable(['名称 | 值', ':--- | ---:', 'A\\|B | 1', '未完成 |'], 0)!
    expect(table.align).toEqual(['left', 'right'])
    expect(table.rows[0]!.map((cell) => cell.text)).toEqual(['A|B', '1'])
    expect(table.rows[1]!.map((cell) => cell.text)).toEqual(['未完成', ''])
  })

  it('leaves incomplete headers and ordinary pipe text as prose', () => {
    expect(answerTable(['名称 | 值'], 0)).toBeUndefined()
    expect(answerTable(['名称 | 值', '普通 | 文字'], 0)).toBeUndefined()
    expect(answerTable(['名称 | 值', '| --- |'], 0)).toBeUndefined()
  })
})
