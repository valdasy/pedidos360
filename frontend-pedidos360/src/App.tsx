import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout'
import { RequireRole } from './components/RequireRole'
import { AdminKafkaPage } from './pages/AdminKafkaPage'
import { AdminMqPage } from './pages/AdminMqPage'
import { AuditPage } from './pages/AuditPage'
import { CatalogPage } from './pages/CatalogPage'
import { DashboardPage } from './pages/DashboardPage'
import { LoginPage } from './pages/LoginPage'
import { OrdersPage } from './pages/OrdersPage'
import { ReportsPage } from './pages/ReportsPage'

/** Rutas del caso Pedidos360. /reports, /audit y /admin/kafka consumen los microservicios de Kafka (Evaluación 3). */
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
            path="/reports"
            element={
              <RequireRole roles={['Admin', 'Operador']}>
                <ReportsPage />
              </RequireRole>
            }
          />
          <Route
            path="/audit"
            element={
              <RequireRole roles={['Admin']}>
                <AuditPage />
              </RequireRole>
            }
          />
          <Route
            path="/admin/kafka"
            element={
              <RequireRole roles={['Admin']}>
                <AdminKafkaPage />
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
