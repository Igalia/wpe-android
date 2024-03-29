// DO NOT EDIT! This test has been generated by /html/canvas/tools/gentest.py.
// OffscreenCanvas test in a worker:2d.text.measure.emHeights
// Description:Testing emHeights
// Note:

importScripts("/resources/testharness.js");
importScripts("/html/canvas/resources/canvas-tests.js");

promise_test(async t => {

  var canvas = new OffscreenCanvas(100, 50);
  var ctx = canvas.getContext('2d');

  var f = new FontFace("CanvasTest", "url('/fonts/CanvasTest.ttf')");
  f.load();
  self.fonts.add(f);
  await self.fonts.ready;
  ctx.font = '50px CanvasTest';
  ctx.direction = 'ltr';
  ctx.align = 'left'
  _assertSame(ctx.measureText('A').emHeightAscent, 37.5, "ctx.measureText('A').emHeightAscent", "37.5");
  _assertSame(ctx.measureText('A').emHeightDescent, 12.5, "ctx.measureText('A').emHeightDescent", "12.5");
  _assertSame(ctx.measureText('A').emHeightDescent + ctx.measureText('A').emHeightAscent, 50, "ctx.measureText('A').emHeightDescent + ctx.measureText('A').emHeightAscent", "50");

  _assertSame(ctx.measureText('ABCD').emHeightAscent, 37.5, "ctx.measureText('ABCD').emHeightAscent", "37.5");
  _assertSame(ctx.measureText('ABCD').emHeightDescent, 12.5, "ctx.measureText('ABCD').emHeightDescent", "12.5");
  _assertSame(ctx.measureText('ABCD').emHeightDescent + ctx.measureText('ABCD').emHeightAscent, 50, "ctx.measureText('ABCD').emHeightDescent + ctx.measureText('ABCD').emHeightAscent", "50");
  t.done();
}, "Testing emHeights");
done();
