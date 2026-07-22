import fs from 'fs';
 
console.time('转换耗时');
const distPath = './dist/index.html';
const htmlText = fs.readFileSync(distPath, 'utf8');
let resultText = htmlText
  .replace(/\s+crossorigin(?:=(["']).*?\1)?/gi, '')
  .replace(/data-src/gi, 'src');

if (/<script\b[^>]*\bnomodule\b/i.test(resultText)) {
  resultText = resultText
    .replace(/<script\b(?=[^>]*\btype=(["'])module\1)[^>]*>[\s\S]*?<\/script>\s*/gi, '')
    .replace(/\s+nomodule\b/gi, '');
}

fs.writeFileSync(distPath, resultText, 'utf8');
console.timeEnd('转换耗时');
