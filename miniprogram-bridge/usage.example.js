/**
 * Minimal usage example — paste into a page.
 *
 * const loc = require('../../utils/location-provider')
 *
 * Page({
 *   async onShow() {
 *     try {
 *       const pos = await loc.getLocation()
 *       console.log('pos', pos)
 *       this.setData({ lat: pos.latitude, lng: pos.longitude })
 *     } catch (e) {
 *       wx.showToast({ title: '定位失败', icon: 'none' })
 *     }
 *   },
 *   onLoad() {
 *     this._stop = loc.onLocationChange((pos) => {
 *       this.setData({ lat: pos.latitude, lng: pos.longitude })
 *     }, 1000)
 *   },
 *   onUnload() {
 *     if (this._stop) this._stop()
 *   }
 * })
 */
