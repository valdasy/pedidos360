import { useCallback, useEffect, useState } from 'react'
import type { Product, ProductRequest } from '../api/types'
import { useApi } from '../api/useApi'
import { useAuth } from '../auth/AuthContext'
import { Card, clp, ErrorBox } from '../components/ui'

const EMPTY: ProductRequest = { sku: '', nombre: '', descripcion: '', precio: 0, stock: 0 }

/** Ruta /catalog (Admin, Operador): ProductCardComponent + ProductFormComponent. */
export function CatalogPage() {
  const api = useApi()
  const { hasRole } = useAuth()
  const esAdmin = hasRole('Admin')
  const [products, setProducts] = useState<Product[]>([])
  const [editing, setEditing] = useState<{ id: number | null; data: ProductRequest } | null>(null)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(() => {
    api.catalog.list().then(setProducts).catch(setError)
  }, [api])
  useEffect(load, [load])

  const save = async () => {
    if (!editing) return
    setError(null)
    try {
      if (editing.id) await api.catalog.update(editing.id, editing.data)
      else await api.catalog.create(editing.data)
      setEditing(null)
      load()
    } catch (e) {
      setError(e)
    }
  }

  const remove = async (p: Product) => {
    if (!confirm(`¿Eliminar ${p.nombre}?`)) return
    try {
      await api.catalog.remove(p.id)
      load()
    } catch (e) {
      setError(e)
    }
  }

  const field = (k: keyof ProductRequest, label: string, type = 'text') => (
    <label>
      {label}
      <input
        type={type}
        value={editing?.data[k] ?? ''}
        onChange={(e) =>
          setEditing((ed) =>
            ed && { ...ed, data: { ...ed.data, [k]: type === 'number' ? Number(e.target.value) : e.target.value } },
          )
        }
      />
    </label>
  )

  return (
    <div className="page">
      <div className="page-header">
        <h1>Catálogo</h1>
        {esAdmin && (
          <button className="btn btn-primary" onClick={() => setEditing({ id: null, data: EMPTY })}>
            Nuevo producto
          </button>
        )}
      </div>
      {!esAdmin && <div className="alert alert-info">Vista de solo lectura: solo un Admin puede modificar el catálogo.</div>}
      <ErrorBox error={error} />

      {editing && (
        <Card title={editing.id ? 'Editar producto' : 'Nuevo producto'}>
          <div className="form-row">
            {field('sku', 'SKU')}
            {field('nombre', 'Nombre')}
          </div>
          <div className="form-row">
            {field('descripcion', 'Descripción')}
            {field('precio', 'Precio (CLP)', 'number')}
            {field('stock', 'Stock', 'number')}
          </div>
          <div className="actions">
            <button className="btn btn-ghost" onClick={() => setEditing(null)}>
              Cancelar
            </button>
            <button className="btn btn-primary" onClick={save}>
              Guardar
            </button>
          </div>
        </Card>
      )}

      <div className="grid">
        {products.map((p) => (
          <div key={p.id} className="product">
            <div className="product-sku">{p.sku}</div>
            <h3>{p.nombre}</h3>
            <p className="muted">{p.descripcion}</p>
            <div className="product-foot">
              <strong>{clp(p.precio)}</strong>
              <span className={p.stock <= 5 ? 'stock low' : 'stock'}>Stock: {p.stock}</span>
            </div>
            {esAdmin && (
              <div className="actions">
                <button
                  className="btn btn-ghost"
                  onClick={() =>
                    setEditing({
                      id: p.id,
                      data: { sku: p.sku, nombre: p.nombre, descripcion: p.descripcion, precio: p.precio, stock: p.stock },
                    })
                  }
                >
                  Editar
                </button>
                <button className="btn btn-ghost danger" onClick={() => remove(p)}>
                  Eliminar
                </button>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
