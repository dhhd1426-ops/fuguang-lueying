/* Runs inside an isolated preview. No native bridges, network, or access to app state. */
(function () {
  'use strict';
  const timeout = (promise, ms, message) => Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(message)), ms))
  ]);
  let prepared = null;
  const paints = () => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  const ready = () => prepared || (prepared = (async () => {
    if (document.fonts) await timeout(document.fonts.ready, 8000, '字体未就绪');
    await timeout(Promise.all(Array.from(document.images).map(img => {
      if (img.complete) {
        if (!img.naturalWidth) throw new Error('有图片未加载');
        return;
      }
      return new Promise((resolve, reject) => {
        img.addEventListener('load', resolve, {once: true});
        img.addEventListener('error', () => reject(new Error('有图片未加载')), {once: true});
      });
    })), 8000, '图片未就绪');
    if (window.__motion) {
      await timeout(Promise.resolve(window.__motion.ready), 8000, '动画初始化未完成');
      if (typeof window.__motion.seek !== 'function') throw new Error('__motion.seek(ms) 未实现');
      if (!(Number.isFinite(window.__motion.durationMs) && window.__motion.durationMs > 0))
        throw new Error('__motion.durationMs 必须为正数');
      return 'controlled';
    }
    if (document.querySelector('canvas,video')) throw new Error('Canvas/WebGL/视频动画需要实现 __motion.seek(ms)');
    if (!document.getAnimations().length) throw new Error('没有可控制时间的动画，请实现 window.__motion');
    return 'web-animations';
  })());
  window.__fgMotionReview = {
    async seek(ms) {
      if (!Number.isFinite(ms) || ms < 0) throw new Error('时间无效');
      const mode = await ready();
      if (mode === 'controlled') {
        if (ms > window.__motion.durationMs) throw new Error('时间超过作品时长');
        await timeout(Promise.resolve(window.__motion.seek(ms)), 8000, '动画定位未完成');
      } else {
        for (const animation of document.getAnimations()) {
          animation.pause();
          animation.currentTime = ms;
        }
      }
      await paints();
      return {mode, time_ms: ms, width: innerWidth, height: innerHeight};
    }
  };
})();
