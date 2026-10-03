// Client gọi backend merchant-demo. Cart giữ client-side; checkout gửi {productId, quantity}[].
const BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8081'

export async function fetchProducts() {
  const r = await fetch(`${BASE}/v1/products`)
  if (!r.ok) throw new Error('Không tải được danh sách sản phẩm')
  return r.json()
}

// idempotencyKey do client sinh & quản lý; server chống trùng bằng UNIQUE(idempotency_key).
export async function placeOrder(idempotencyKey, items) {
  const r = await fetch(`${BASE}/v1/orders`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    body: JSON.stringify({ items }),
  })
  const body = await r.json().catch(() => null)
  if (!r.ok) {
    const err = new Error(body?.detail || 'Đặt hàng thất bại')
    err.body = body
    throw err
  }
  return body
}

// Phase 2: yêu cầu merchant khởi tạo thanh toán ở Payment Hub.
// Gọi nhiều lần an toàn — còn payment sống thì server trả lại ĐÚNG link cũ (reused=true).
export async function initiatePayment(orderCode) {
  const r = await fetch(`${BASE}/v1/orders/${orderCode}/payment`, { method: 'POST' })
  const body = await r.json().catch(() => null)
  if (!r.ok) {
    const err = new Error(body?.detail || 'Không khởi tạo được thanh toán')
    err.body = body
    throw err
  }
  return body
}

export async function fetchOrder(orderCode) {
  const r = await fetch(`${BASE}/v1/orders/${orderCode}`)
  if (!r.ok) throw new Error('Không tải được đơn hàng')
  return r.json()
}
