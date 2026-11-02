"""Browser checks. Run with a local server and Python's playwright package."""
import os
import unittest
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / '.superpowers' / 'implementation'
URL = os.environ.get('CAROZERRA_URL', 'http://localhost:8000')


class WebPlayer(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runtime = sync_playwright().start()
        cls.browser = cls.runtime.chromium.launch(executable_path='/usr/bin/google-chrome', headless=True, args=['--no-sandbox'])
        ARTIFACTS.mkdir(parents=True, exist_ok=True)

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.runtime.stop()

    def setUp(self):
        self.context = self.browser.new_context(viewport={'width': 1280, 'height': 900}, accept_downloads=True)
        self.context.route('https://www.googletagmanager.com/**', lambda route: route.abort())
        self.context.route('https://api.github.com/**', lambda route: route.abort())
        self.ad_requests = []
        def mock_ad(route):
            self.ad_requests.append(route.request.url)
            if '/21/' in route.request.url:
                body = "document.querySelector('[id^=container-]').textContent = 'Test native ad';"
            else:
                body = "document.write('<iframe title=\"Test banner\" width=\"' + atOptions.width + '\" height=\"' + atOptions.height + '\"></iframe>');"
            route.fulfill(content_type='application/javascript', body=body)
        self.context.route('https://bauval.org/**', mock_ad)
        self.page = self.context.new_page()
        self.errors = []
        self.page.on('pageerror', lambda error: self.errors.append(str(error)))
        self.page.goto(URL)
        self.page.wait_for_function("document.querySelector('#scrub').max === '59' && document.querySelector('#meta').textContent.includes('movie1.lkd')")

    def tearDown(self):
        self.assertEqual(self.errors, [])
        self.context.close()

    def test_ads_choose_one_banner_for_available_width(self):
        banner = self.page.locator('#adBanner')
        banner.scroll_into_view_if_needed(timeout=2000)
        expect(banner).to_have_attribute('width', '728')
        self.page.wait_for_function("document.querySelector('#adBanner').contentDocument.querySelector('iframe') !== null")
        self.assertEqual(len([u for u in self.ad_requests if '/22/' in u]), 1)
        self.assertTrue(any('6d33916843669043d309748d1f48ef51' in u for u in self.ad_requests))
        for width in [320, 390, 768]:
            self.page.set_viewport_size({'width': width, 'height': 900})
            expect(banner).to_have_attribute('width', '320')
            self.assertTrue(self.page.evaluate('document.documentElement.scrollWidth <= innerWidth'), width)
        self.page.wait_for_function("document.querySelector('#adBanner').contentDocument.querySelector('iframe') !== null")
        self.assertEqual(len([u for u in self.ad_requests if '/22/' in u]), 2)
        self.assertTrue(any('0c4d2ae1997df130389651f3751f9bf1' in u for u in self.ad_requests))
        self.page.locator('#nativeAd').scroll_into_view_if_needed()
        expect(self.page.locator('#container-ea3a9dd4947ab23327f651a2e40a6278')).to_have_text('Test native ad')
        self.assertEqual(len([u for u in self.ad_requests if '/21/' in u]), 1)

    def test_blocked_ads_leave_player_usable(self):
        self.context.route('https://bauval.org/**', lambda route: route.abort())
        self.page.reload()
        self.page.wait_for_function("document.querySelector('#meta').textContent.includes('movie1.lkd')")
        self.page.get_by_role('button', name='Next animation').click()
        expect(self.page.locator('#meta')).to_contain_text('movie2.lkd')

    def test_category_browsing_preserves_player_and_navigation_reveals_clip(self):
        self.page.get_by_role('button', name='Backgrounds', exact=True).click(timeout=2000)
        expect(self.page.locator('#meta')).to_contain_text('movie1.lkd')
        self.page.locator('#files button').first.click()
        expect(self.page.locator('#meta')).to_contain_text('alt_bgv1.lkd')
        self.page.get_by_role('button', name='Previous animation').click()
        expect(self.page.locator('#meta')).to_contain_text('alt_wrc.lkd')
        expect(self.page.get_by_role('button', name='Movies', exact=True)).to_have_attribute('aria-pressed', 'true')

    def test_mobile_mode_switch_preserves_frame(self):
        self.page.set_viewport_size({'width': 390, 'height': 844})
        self.page.get_by_text('Playback settings', exact=True).click(timeout=2000)
        self.page.locator('#scrub').fill('17')
        self.page.get_by_role('button', name='View full stereo').click()
        expect(self.page.locator('#scrub')).to_have_value('17')
        expect(self.page.locator('#play')).to_have_accessible_name('Play')
        self.page.get_by_role('button', name='View animation', exact=True).click()
        expect(self.page.locator('#scrub')).to_have_value('17')

    def test_only_visible_category_fetches_and_viewports_fit(self):
        urls = self.page.evaluate("performance.getEntriesByType('resource').filter(r=>r.name.includes('/clips/')).map(r=>r.name)")
        self.assertFalse(any('/still_' in u or '/color_' in u or '/meter_' in u or '/alt_bgv' in u for u in urls), urls)
        for width in [320, 390, 640, 768, 1024, 1440]:
            self.page.set_viewport_size({'width': width, 'height': 900})
            self.page.screenshot(path=str(ARTIFACTS / f'page-{width}.png'), full_page=True)
            self.assertTrue(self.page.evaluate('document.documentElement.scrollWidth <= innerWidth'), width)
            if width >= 760:
                self.assertTrue(self.page.get_by_role('link', name='GitHub', exact=True).is_visible())
            else:
                expect(self.page.locator('#mobileMenu > summary')).to_be_visible()
                if width >= 640:
                    self.page.locator('#mobileMenu > summary').click()
                    self.assertTrue(self.page.get_by_role('link', name='GitHub', exact=True).is_visible())
                    self.page.locator('#mobileMenu > summary').click()

    def test_gallery_posters_do_not_download_full_animations(self):
        self.page.locator('#files').scroll_into_view_if_needed()
        self.page.wait_for_timeout(300)
        clips = self.page.evaluate("performance.getEntriesByType('resource').filter(r=>r.name.includes('/clips/')).map(r=>r.name.split('/').pop())")
        self.assertEqual(clips, ['movie1.lkd'])

    def test_slider_arrows_and_dialog_do_not_navigate(self):
        self.page.get_by_text('Playback settings', exact=True).click()
        self.page.locator('#fps').focus()
        self.page.keyboard.press('ArrowRight')
        expect(self.page.locator('#fps')).to_have_value('17')
        expect(self.page.locator('#meta')).to_contain_text('movie1.lkd')
        self.page.get_by_role('button', name='Changelog', exact=True).click()
        self.page.keyboard.press('ArrowRight')
        expect(self.page.locator('#meta')).to_contain_text('movie1.lkd')
        self.page.keyboard.press('Escape')
        expect(self.page.get_by_role('button', name='Changelog', exact=True)).to_be_focused()

    def test_bad_file_preserves_clip_then_multiple_custom_files_work(self):
        self.page.locator('#file').set_input_files({'name': 'bad.lkd', 'mimeType': 'application/octet-stream', 'buffer': b'bad file'})
        expect(self.page.locator('#status')).to_contain_text('bad.lkd')
        expect(self.page.locator('#meta')).to_contain_text('movie1.lkd')
        self.page.locator('#file').set_input_files([
            {'name': 'custom-one.lkd', 'mimeType': 'application/octet-stream', 'buffer': (ROOT / 'assets/clips/movie1.lkd').read_bytes()},
            {'name': 'custom-two.lkd', 'mimeType': 'application/octet-stream', 'buffer': (ROOT / 'assets/clips/movie2.lkd').read_bytes()},
        ])
        expect(self.page.get_by_role('button', name='Custom', exact=True)).to_be_visible()
        expect(self.page.locator('#files button')).to_have_count(2)

    def test_playback_settings_change_the_live_player(self):
        self.page.get_by_role('button', name='Pause', exact=True).click()
        self.page.get_by_text('Playback settings', exact=True).click()
        self.page.locator('#scrub').fill('23')
        expect(self.page.locator('#scrubv')).to_have_text('23 / 59')
        self.page.locator('#fx').uncheck()
        self.assertNotIn('fx', self.page.locator('#oel').get_attribute('class').split())
        self.page.locator('#fps').fill('24')
        expect(self.page.locator('#fpsv')).to_have_text('24 fps')
        self.page.get_by_role('button', name='Play', exact=True).click()
        self.page.wait_for_function("document.querySelector('#scrub').value !== '23'")

    def test_gif_export_locks_selection_and_saves_a_real_file(self):
        self.page.get_by_text('Export', exact=True).click()
        with self.page.expect_download(timeout=30000) as event:
            self.page.get_by_role('button', name='Save as GIF').click()
            expect(self.page.locator('#next')).to_be_disabled()
            self.page.keyboard.press('ArrowRight')
            expect(self.page.locator('#meta')).to_contain_text('movie1.lkd')
        download=event.value
        data=Path(download.path()).read_bytes()
        self.assertTrue(data.startswith(b'GIF89a'))
        self.assertGreater(len(data), 1000)
        expect(self.page.locator('#next')).to_be_enabled()

    def test_webm_export_saves_recording(self):
        self.page.get_by_text('Export', exact=True).click()
        with self.page.expect_download(timeout=30000) as event:
            self.page.get_by_role('button', name='Save as WebM').click()
        self.assertTrue(Path(event.value.path()).read_bytes().startswith(bytes.fromhex('1a45dfa3')))

    def test_unsupported_webm_stays_disabled_after_gif(self):
        self.page.add_init_script('window.MediaRecorder = undefined')
        self.page.reload()
        self.page.wait_for_function("document.querySelector('#meta').textContent.includes('movie1.lkd')")
        self.page.get_by_text('Export', exact=True).click()
        expect(self.page.locator('#expWebm')).to_be_disabled()
        with self.page.expect_download(timeout=30000):
            self.page.locator('#expGif').click()
        expect(self.page.locator('#expWebm')).to_be_disabled()

    def test_changelog_content_and_mobile_menu(self):
        self.page.get_by_role('button', name='Changelog', exact=True).click()
        expect(self.page.locator('#clogBody h3').first).to_be_visible()
        self.page.keyboard.press('Escape')
        self.page.set_viewport_size({'width': 320, 'height': 720})
        self.page.locator('#mobileMenu > summary').click()
        self.page.keyboard.press('Escape')
        self.assertFalse(self.page.locator('#mobileMenu').evaluate('(el)=>el.open'))
        expect(self.page.locator('#mobileMenu > summary')).to_be_focused()
        self.page.locator('#mobileMenu > summary').click()
        self.page.get_by_role('link', name='About Carozerra').click()
        expect(self.page.locator('#about h2').first).to_be_in_viewport()
        self.assertFalse(self.page.locator('#mobileMenu').evaluate('(el)=>el.open'))

    def test_reduced_motion_does_not_fetch_hover_previews(self):
        self.page.emulate_media(reduced_motion='reduce')
        self.page.locator('#files button').nth(1).hover()
        self.page.wait_for_timeout(250)
        clips=self.page.evaluate("performance.getEntriesByType('resource').filter(r=>r.name.includes('/clips/')).map(r=>r.name.split('/').pop())")
        self.assertEqual(clips, ['movie1.lkd'])

    def test_slow_upload_cannot_override_a_newer_selection(self):
        self.page.evaluate("""() => {
          const original=File.prototype.arrayBuffer;
          File.prototype.arrayBuffer=function(){
            return new Promise(resolve=>setTimeout(()=>resolve(original.call(this)), 700));
          };
        }""")
        self.page.locator('#file').set_input_files({'name': 'slow.lkd', 'mimeType': 'application/octet-stream', 'buffer': (ROOT/'assets/clips/movie1.lkd').read_bytes()})
        self.page.get_by_role('button', name='Next animation').click()
        expect(self.page.locator('#meta')).to_contain_text('movie2.lkd')
        self.page.wait_for_timeout(900)
        expect(self.page.locator('#meta')).to_contain_text('movie2.lkd')

    def test_mobile_settings_have_touch_targets(self):
        self.page.set_viewport_size({'width': 320, 'height': 720})
        self.page.get_by_text('Playback settings', exact=True).click()
        for selector in ['#fps', '#scrub', 'label[for="fx"]']:
            self.assertGreaterEqual(self.page.locator(selector).bounding_box()['height'], 44, selector)


if __name__ == '__main__':
    unittest.main()
