// TEST ONLY: deterministic HTTP protocol fixture. Never a real model or quality evaluation.
import http from 'node:http'
const port = Number(process.env.FIXTURE_PORT || 18090)
const terms = ['年假', '试用期', '审批', '报销', '设备', '远程', '安全', '培训', '联系', '工作']
http.createServer(async (req, res) => {
  let raw = ''
  for await (const part of req) raw += part
  let body
  try { body = JSON.parse(raw) } catch { res.writeHead(400).end(); return }
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  if (req.url === '/v1/embeddings') {
    res.end(JSON.stringify({ data: body.input.map((text, index) => {
      const base = terms.map(term => text.includes(term) ? 1 : 0).concat([0.1])
      return {
        index,
        embedding: Array.from({ length: body.dimensions }, (_, dimension) => base[dimension] ?? 0.1),
      }
    }) }))
  } else if (req.url === '/v1/chat/completions') {
    const content = '[测试替身，非真实模型回答] 试用期员工暂不能申请年假。[S1]'
    if (body.stream) {
      res.setHeader('Content-Type', 'text/event-stream; charset=utf-8')
      res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: content.slice(0, 12) }, finish_reason: null }] })}\n\n`)
      res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: content.slice(12) }, finish_reason: 'stop' }] })}\n\n`)
      res.end('data: [DONE]\n\n')
    } else res.end(JSON.stringify({ choices: [{ finish_reason: 'stop', message: { content } }] }))
  } else res.writeHead(404).end()
}).listen(port, '127.0.0.1', () => console.log('TEST ONLY model HTTP fixture on ' + port))
