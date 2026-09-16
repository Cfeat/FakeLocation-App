/**
 * Drop-in location provider for YOUR WeChat mini program (dev / test builds only).
 *
 * Why: WeChat often ignores Android system mock GPS, so wx.getLocation will not change
 * even when Fake Location app is simulating. Poll this LAN bridge instead.
 *
 * Setup:
 * 1. Phone and the machine running WeChat DevTools / test device share the same Wi-Fi
 *    (or use a real phone with the mini program; phone IP is shown in the Android app).
 * 2. In WeChat DevTools: 详情 → 本地设置 → 勾选「不校验合法域名、web-view、TLS、HTTPS」
 * 3. Paste BRIDGE_URL from the Android app ("复制桥接地址"), e.g.
 *    http://192.168.1.23:18765/location
 * 4. Replace direct wx.getLocation / wx.onLocationChange with this module.
 * 5. Set USE_BRIDGE = false before production release.
 */

const USE_BRIDGE = true // MUST be false in production release
// Paste from Android app button "复制桥接地址"
const BRIDGE_URL = 'http://127.0.0.1:18765/location'

let watchTimer = null
const watchListeners = []

function requestBridge() {
  return new Promise((resolve, reject) => {
    wx.request({
      url: BRIDGE_URL,
      method: 'GET',
      timeout: 3000,
      success(res) {
        if (res.statusCode === 200 && res.data && res.data.latitude != null) {
          resolve({
            latitude: res.data.latitude,
            longitude: res.data.longitude,
            speed: res.data.speed || 0,
            accuracy: res.data.accuracy || 8,
            altitude: res.data.altitude || 0,
            horizontalAccuracy: res.data.horizontalAccuracy || 8,
            verticalAccuracy: 8,
            provider: 'fakelocation-bridge',
            running: !!res.data.running
          })
        } else {
          reject(new Error('bridge empty; start simulation in Fake Location app'))
        }
      },
      fail: reject
    })
  })
}

function requestWx(type) {
  return new Promise((resolve, reject) => {
    wx.getLocation({
      type: type || 'gcj02',
      isHighAccuracy: true,
      success: resolve,
      fail: reject
    })
  })
}

/** Same shape as wx.getLocation success result (gcj02). */
function getLocation(options) {
  const type = (options && options.type) || 'gcj02'
  if (USE_BRIDGE) {
    return requestBridge().catch((err) => {
      console.warn('[location-provider] bridge failed, fallback wx', err)
      return requestWx(type)
    })
  }
  return requestWx(type)
}

/**
 * Poll bridge every intervalMs and invoke listener like wx.onLocationChange.
 * Returns a stop function.
 */
function onLocationChange(listener, intervalMs) {
  watchListeners.push(listener)
  const ms = intervalMs || 1000
  if (USE_BRIDGE) {
    if (!watchTimer) {
      watchTimer = setInterval(() => {
        requestBridge()
          .then((pos) => {
            watchListeners.forEach((fn) => {
              try { fn(pos) } catch (e) { console.error(e) }
            })
          })
          .catch(() => { /* keep polling */ })
      }, ms)
    }
    return function stop() {
      const i = watchListeners.indexOf(listener)
      if (i >= 0) watchListeners.splice(i, 1)
      if (watchListeners.length === 0 && watchTimer) {
        clearInterval(watchTimer)
        watchTimer = null
      }
    }
  }

  wx.startLocationUpdate({ type: 'gcj02' })
  wx.onLocationChange(listener)
  return function stop() {
    wx.offLocationChange(listener)
  }
}

module.exports = {
  USE_BRIDGE,
  BRIDGE_URL,
  getLocation,
  onLocationChange
}
