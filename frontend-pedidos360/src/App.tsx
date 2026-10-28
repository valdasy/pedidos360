import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout'
import { RequireRole } from './components/RequireRole'
import { AdminMqPage } from './pages/AdminMqPage'
import { CatalogPage } from './pages/CatalogPage'
import { DashboardPage } from './pages/DashboardPage'
import { LoginPage } from './pages/LoginPage'
import { OrdersPage } from './pages/OrdersPage'

/** Rutas del caso Pedidos360 (sin /reports ni /audit: quedan fuera del alcance, usan Kafka). */
export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          element={
            <RequireRole>
              <Layout />
            </RequireRole>
          }
        >
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/orders" element={<OrdersPage />} />
          <Route
            path="/catalog"
            element={
              <RequireRole roles={['Admin', 'Operador']}>
                <CatalogPage />
              </RequireRole>
            }
          />
          <Route
            path="/admin/mq"
            element={
              <RequireRole roles={['Admin']}>
                <AdminMqPage />
              </RequireRole>
            }
          />
        </Route>
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
