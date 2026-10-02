// 正则可用性回归（2026-10-02 修 RhinoServiceWarmUp 之后）
var lit = /^front$/i.test('Front');          // 字面量 + i 标志
var ctor = new RegExp('a.c').test('abc');    // 构造函数
var grp = /([0-9]+)/.exec('a12b')[1];        // 捕获组
var rep = 'a1b2'.replace(/[0-9]/g, '#');     // replace + g
var typ = typeof RegExp;
console.log('REGEXP_PROBE lit=' + lit + ' ctor=' + ctor + ' grp=' + grp + ' rep=' + rep + ' typeof=' + typ);
var r = {};
r.lit = lit; r.grp = grp; r.rep = rep; r.typeof = typ;
r;
