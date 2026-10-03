import { useEffect, useMemo, useState } from 'react'
import { fetchProducts, placeOrder, initiatePayment, fetchOrder } from './api.js'

const fmt = (n) => n.toLocaleString('vi-VN')
const newIdemKey = () =>
  'idem_' + (crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(36).slice(2))

export default function App() {
  const [products, setProducts] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadErr, setLoadErr] = useState(null)

  const [view, setView] = useState('products')   // products | cart | checkout | result
  const [cart, setCart] = useState({})            // { [productId]: qty }  (client-side)
  const [idemKey, setIdemKey] = useState(null)    // sinh khi vào checkout
  const [order, setOrder] = useState(null)
  const [placing, setPlacing] = useState(false)
  const [payErr, setPayErr] = useState(null)
  const [returnInfo, setReturnInfo] = useState(null)  // ?status=... khi Hub redirect về

  useEffect(() => {
    fetchProducts()
      .then((p) => setProducts(p))
      .catch((e) => setLoadErr(e.message))
      .finally(() => setLoading(false))
  }, [])

  const byId = useMemo(() => Object.fromEntries(products.map((p) => [p.id, p])), [products])
  const lines = Object.entries(cart)
    .filter(([, q]) => q > 0)
    .map(([id, q]) => ({ product: byId[Number(id)], qty: q }))
    .filter((l) => l.product)
  const count = lines.reduce((s, l) => s + l.qty, 0)
  const total = lines.reduce((s, l) => s + l.product.priceAmount * l.qty, 0)
  const currency = lines[0]?.product.currency || 'VND'

  const add = (id) => setCart((c) => ({ ...c, [id]: (c[id] || 0) + 1 }))
  const setQty = (id, d) =>
    setCart((c) => {
      const q = Math.max(0, (c[id] || 0) + d)
      const next = { ...c }
      if (q === 0) delete next[id]
      else next[id] = q
      return next
    })
  const removeItem = (id) =>
    setCart((c) => {
      const next = { ...c }
      delete next[id]
      return next
    })

  const goCheckout = () => {
    setIdemKey(newIdemKey())
    setPayErr(null)
    setView('checkout')
    window.scrollTo(0, 0)
  }

  // Hai bước TÁCH RIÊNG có chủ đích:
  //   ① tạo đơn (nội bộ merchant, phải luôn xong)
  //   ② khởi tạo thanh toán ở Hub (qua mạng, có thể hỏng và thử lại được)
  // Đơn đã tạo rồi thì bước ② hỏng vẫn không mất đơn — bấm lại là gọi Hub lại.
  const pay = async () => {
    setPlacing(true)
    setPayErr(null)
    try {
      const items = lines.map((l) => ({ productId: l.product.id, quantity: l.qty }))
      const o = await placeOrder(idemKey, items)
      setOrder(o)
      setCart({})

      const handoff = await initiatePayment(o.orderCode)
      // checkout_url là OPAQUE: dùng NGUYÊN XI, không tự ghép lại từ token.
      window.location.href = handoff.checkoutUrl
    } catch (e) {
      setPayErr(e.message)
      setPlacing(false)
    }
  }

  // Thử thanh toán lại cho đơn đã có (khách quay về mà chưa trả, hoặc link cũ hết hạn).
  const retryPayment = async (orderCode) => {
    setPlacing(true)
    setPayErr(null)
    try {
      const handoff = await initiatePayment(orderCode)
      window.location.href = handoff.checkoutUrl
    } catch (e) {
      setPayErr(e.message)
      setPlacing(false)
    }
  }

  const go = (v) => { setView(v); window.scrollTo(0, 0) }

  // Hub đưa khách quay về ĐÂY sau khi thanh toán/hủy:
  //   /orders/{orderCode}?payment_ref=...&status=CANCELLED|CREATED|...
  // ⚠️ Query param này CHỈ để hiển thị nhanh. Trạng thái THẬT lấy từ backend merchant
  // (và backend chỉ tin webhook của Hub — Increment 5), không tin thanh địa chỉ.
  useEffect(() => {
    const match = window.location.pathname.match(/^\/orders\/([^/]+)/)
    if (!match) return
    const orderCode = match[1]
    const params = new URLSearchParams(window.location.search)
    setReturnInfo({ status: params.get('status'), paymentRef: params.get('payment_ref') })
    fetchOrder(orderCode)
      .then((o) => { setOrder(o); setView('result') })
      .catch((e) => setLoadErr(e.message))
  }, [])

  return (
    <>
      <header>
        <div className="wrap bar">
          <div className="brand">
            <h1>Chợ Phiên</h1>
            <span className="tag">cửa hàng demo · Payment Hub</span>
          </div>
          <button className="cartbtn" onClick={() => go('cart')} aria-label="Xem giỏ hàng">
            🛒 <span className="n">{count}</span>
            <span className="amt">{fmt(total)}&nbsp;₫</span>
          </button>
        </div>
      </header>

      <main className="wrap">
        {view === 'products' && (
          <ProductsView loading={loading} loadErr={loadErr} products={products} onAdd={add} />
        )}
        {view === 'cart' && (
          <CartView lines={lines} count={count} total={total} onGo={go}
            onInc={(id) => setQty(id, +1)} onDec={(id) => setQty(id, -1)} onRemove={removeItem}
            onCheckout={goCheckout} />
        )}
        {view === 'checkout' && (
          <CheckoutView lines={lines} total={total} currency={currency} idemKey={idemKey}
            placing={placing} payErr={payErr} onBack={() => go('cart')} onPay={pay} />
        )}
        {view === 'result' && order && (
          <ResultView order={order} returnInfo={returnInfo} placing={placing} payErr={payErr}
            onRetry={() => retryPayment(order.orderCode)}
            onHome={() => { window.history.replaceState({}, '', '/'); go('products') }} />
        )}
      </main>

      <footer className="wrap">
        Merchant Demo — duyệt · giỏ · tạo Order · <b>chuyển sang Payment Hub</b> (Phase 2). Webhook cập nhật trạng thái: Increment 5.
      </footer>
    </>
  )
}

function ProductsView({ loading, loadErr, products, onAdd }) {
  return (
    <div className="view">
      <p className="eyebrow">Cửa hàng</p>
      <h2 className="vtitle">Sản phẩm</h2>
      <p className="vsub">Chọn món và thêm vào giỏ. Giá niêm yết bằng VND (số nguyên, không phần lẻ).</p>
      {loading && <div className="state">Đang tải sản phẩm…</div>}
      {loadErr && <div className="state err">{loadErr}</div>}
      {!loading && !loadErr && (
        <div className="grid">
          {products.map((p) => (
            <article className="card" key={p.id}>
              <div className="thumb">{p.imageEmoji || '🛍️'}</div>
              <div className="body">
                <div className="name">{p.name}</div>
                <div className="desc">{p.description}</div>
                <div className="foot">
                  <span className="price">{fmt(p.priceAmount)}<span className="cur">₫</span></span>
                  <button className="add" onClick={() => onAdd(p.id)}>Thêm vào giỏ</button>
                </div>
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  )
}

function CartView({ lines, count, total, onGo, onInc, onDec, onRemove, onCheckout }) {
  if (!lines.length) {
    return (
      <div className="view">
        <button className="back" onClick={() => onGo('products')}>← Tiếp tục mua</button>
        <div className="empty">
          <div className="big">🛒</div>
          <h3>Giỏ hàng trống</h3>
          <p>Chưa có món nào. <button className="linkbtn" onClick={() => onGo('products')}>Xem sản phẩm</button></p>
        </div>
      </div>
    )
  }
  return (
    <div className="view">
      <button className="back" onClick={() => onGo('products')}>← Tiếp tục mua</button>
      <p className="eyebrow">Giỏ hàng</p>
      <h2 className="vtitle">Giỏ của bạn</h2>
      <div className="layout">
        <div className="lines">
          {lines.map(({ product: p, qty }) => (
            <div className="line" key={p.id}>
              <div className="ic">{p.imageEmoji || '🛍️'}</div>
              <div className="info">
                <div className="nm">{p.name}</div>
                <div className="unit">{fmt(p.priceAmount)} ₫ / cái</div>
              </div>
              <div className="right">
                <div className="lt">{fmt(p.priceAmount * qty)} ₫</div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <div className="stepper">
                    <button onClick={() => onDec(p.id)} aria-label="Giảm">−</button>
                    <span className="q">{qty}</span>
                    <button onClick={() => onInc(p.id)} aria-label="Tăng">+</button>
                  </div>
                  <button className="rm" onClick={() => onRemove(p.id)}>Xóa</button>
                </div>
              </div>
            </div>
          ))}
        </div>
        <aside className="summary">
          <h3>Tóm tắt</h3>
          <div className="srow"><span>Số món</span><span className="v">{count}</span></div>
          <div className="srow"><span>Tạm tính</span><span className="v">{fmt(total)} ₫</span></div>
          <div className="srow total"><span className="lbl">Tổng cộng</span><span className="v">{fmt(total)} ₫</span></div>
          <button className="cta" onClick={onCheckout}>Checkout</button>
          <p className="note">Tổng tiền được tính lại ở <b>server</b> (không tin số client gửi). Tiền lưu dạng <code>số nguyên + VND</code>.</p>
        </aside>
      </div>
    </div>
  )
}

function CheckoutView({ lines, total, currency, idemKey, placing, payErr, onBack, onPay }) {
  return (
    <div className="view">
      <button className="back" onClick={onBack}>← Quay lại giỏ</button>
      <p className="eyebrow">Xác nhận</p>
      <h2 className="vtitle">Checkout</h2>
      <p className="vsub">Kiểm tra lại đơn. Bấm “Thanh toán” sẽ <b>tạo một Order</b> ở trạng thái chờ thanh toán.</p>
      <div className="layout">
        <div className="review">
          {lines.map(({ product: p, qty }) => (
            <div className="rl" key={p.id}>
              <span className="nm">{p.name} <small>× {qty}</small></span>
              <span className="v">{fmt(p.priceAmount * qty)} ₫</span>
            </div>
          ))}
          <div className="rl">
            <span className="nm"><b>Tổng cộng</b></span>
            <span className="v" style={{ fontSize: 17 }}>{fmt(total)} ₫</span>
          </div>
        </div>
        <aside className="summary">
          <h3>Thanh toán</h3>
          <div className="srow"><span>Tổng đơn</span><span className="v">{fmt(total)} ₫</span></div>
          <div className="srow"><span>Tiền tệ</span><span className="v">{currency}</span></div>
          <button className="cta" onClick={onPay} disabled={placing}>
            {placing ? 'Đang xử lý…' : 'Thanh toán'}
          </button>
          {payErr && <div className="err">{payErr}</div>}
          <p className="note"><b>Idempotency-Key</b> (sinh khi vào màn này):<br /><code>{idemKey}</code><br />Bấm 2 lần cùng key → không tạo đơn trùng.</p>
        </aside>
      </div>
    </div>
  )
}

function ResultView({ order, returnInfo, placing, payErr, onRetry, onHome }) {
  const n = order.items.reduce((s, i) => s + i.quantity, 0)

  // returnInfo chỉ có khi khách vừa từ Payment Hub quay về.
  // Nó đến từ QUERY PARAM nên CHỈ dùng để hiển thị nhanh — không phải nguồn sự thật.
  const outcome = {
    CAPTURED:  { icon: '✓', title: 'Thanh toán thành công', tone: 'ok' },
    CANCELLED: { icon: '✕', title: 'Bạn đã hủy thanh toán', tone: 'warn' },
    EXPIRED:   { icon: '⏳', title: 'Link thanh toán đã hết hạn', tone: 'warn' },
    FAILED:    { icon: '✕', title: 'Thanh toán thất bại', tone: 'warn' },
  }[returnInfo?.status] || { icon: '✓', title: 'Đã tạo đơn hàng', tone: 'ok' }

  const payable = !['PAID', 'FAILED', 'EXPIRED', 'CANCELLED'].includes(order.status)

  return (
    <div className="view">
      <div className="result">
        <div className="head">
          <div className="check">{outcome.icon}</div>
          <h2>{outcome.title}</h2>
          <p>
            {returnInfo
              ? 'Trạng thái hiển thị lấy từ link quay về. Trạng thái CHÍNH THỨC của đơn do Payment Hub báo qua webhook (Increment 5).'
              : 'Đơn đã được lưu, đang chờ thanh toán.'}
          </p>
        </div>
        <div className="flow">
          <span className="chip on">CREATED</span><span className="arrow">→</span>
          <span className={order.status === 'AWAITING_PAYMENT' ? 'chip on' : 'chip next'}>AWAITING_PAYMENT</span>
          <span className="arrow">→</span>
          <span className="chip">PAID / FAILED</span>
        </div>
        <div className="kv">
          <div className="row"><span className="k">Mã đơn</span><span className="val">{order.orderCode}</span></div>
          <div className="row"><span className="k">Trạng thái đơn</span><span className="val"><span className="badge"><span className="dot"></span>{order.status}</span></span></div>
          {returnInfo?.paymentRef && (
            <div className="row"><span className="k">Mã thanh toán</span><span className="val">{returnInfo.paymentRef}</span></div>
          )}
          <div className="row"><span className="k">Số món</span><span className="val">{n}</span></div>
          <div className="row"><span className="k">Tổng tiền</span><span className="val">{fmt(order.totalAmount)} ₫</span></div>
        </div>
        <div className="foot">
          {payErr && <p className="note" style={{ color: '#b42318' }}>{payErr}</p>}
          <p className="note">
            🔒 Giá đã <b>chốt</b> lúc tạo đơn (snapshot ở server). Tổng tiền do <b>server</b> tính.
            {payable && ' Chưa trả xong thì bấm "Thanh toán lại" — hệ thống dùng lại link cũ nếu còn hạn, hết hạn thì tạo lệnh mới.'}
          </p>
          {payable && (
            <button className="cta" onClick={onRetry} disabled={placing}>
              {placing ? 'Đang chuyển…' : 'Thanh toán lại'}
            </button>
          )}
          <button className="cta" onClick={onHome}>Về cửa hàng</button>
        </div>
      </div>
    </div>
  )
}
