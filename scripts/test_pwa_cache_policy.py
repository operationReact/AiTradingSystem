from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
NGINX = ROOT / 'apps/trading_client/deploy/nginx.conf'
DOCKERFILE = ROOT / 'apps/trading_client/Dockerfile'
KILL_SWITCH = ROOT / 'apps/trading_client/deploy/flutter_service_worker.js'
INDEX = ROOT / 'apps/trading_client/web/index.html'


class PwaCachePolicyTest(unittest.TestCase):
    def test_release_entry_points_are_not_persistently_cached(self):
        text = NGINX.read_text()
        for path in ('/index.html', '/flutter_bootstrap.js', '/flutter.js', '/main.dart.js', '/flutter_service_worker.js'):
            self.assertIn(f'location = {path}', text)
        self.assertNotIn('public, max-age=3600', text)
        self.assertIn('no-store, no-cache, must-revalidate, max-age=0', text)

    def test_packaged_service_worker_clears_cache_and_unregisters(self):
        docker = DOCKERFILE.read_text()
        worker = KILL_SWITCH.read_text()
        self.assertIn('deploy/flutter_service_worker.js /usr/share/nginx/html/flutter_service_worker.js', docker)
        self.assertIn('caches.keys()', worker)
        self.assertIn('caches.delete', worker)
        self.assertIn('self.registration.unregister()', worker)
        self.assertNotIn("addEventListener('fetch'", worker)

    def test_offline_web_ui_fails_closed(self):
        text = INDEX.read_text()
        self.assertIn('Offline — trading actions unavailable', text)
        self.assertIn("window.addEventListener('offline'", text)
        self.assertIn("document.documentElement.classList.toggle('ats-offline'", text)
        self.assertIn('z-index: 2147483647', text)


if __name__ == '__main__':
    unittest.main()
