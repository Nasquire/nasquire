import { createRoot } from 'react-dom/client'
import App from './App'
import './styles.css'
import { installScrollbars } from './scrollbars'
installScrollbars()
createRoot(document.getElementById('root')!).render(<App />)
