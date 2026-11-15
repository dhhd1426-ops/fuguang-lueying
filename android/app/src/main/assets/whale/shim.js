/* Cubism Core 5.3 起 renderOrders 从 drawables 挪到了 model 上（含 offscreen）。
   pixi-live2d-display 0.4.0 里的框架还读 drawables.renderOrders，这里补回去。 */
(function(){var C=window.Live2DCubismCore;if(!C||!C.Model)return;var f=C.Model.fromMoc;
  C.Model.fromMoc=function(m){var r=f.apply(this,arguments);
    if(r&&r.drawables&&!('renderOrders' in r.drawables)&&r.renderOrders){var d=r.drawables;
      Object.defineProperty(d,'renderOrders',{configurable:true,get:function(){return r.renderOrders.length===d.count?r.renderOrders:r.renderOrders.subarray(0,d.count);}});}
    return r;};})();
