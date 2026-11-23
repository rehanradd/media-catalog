#!/usr/bin/env python3
"""test_wp_rest.py — unit tests for the shared WP REST helpers.

Offline only: every fixture is transcribed from a real live WP REST
response captured 2026-11-23 (bolly4u "Son Of 2026", ssrmovies
"Kapil Show S05") — real titles, labels, link hosts and shapes, no
live network. The fake ``get_json`` stands in for the transport.
"""

import unittest

import wp_rest


# Real info block, transcribed from bolly4u post 146083 ("Son Of 2026",
# captured live 2026-11-23): the content text carries every field.
SON_OF_TEXT = (
    "Son Of 2026 WEB-DL UNCUT Hindi Dual Audio ORG Full Movie Download "
    "[Hindi – Tamil ] 1080p, 720p & 480p. Son Of 2026 This is a South "
    "Hindi Movie and available in Hindi 1080p, 720p & 480p qualities. "
    "This is one of the best movies based on Romance, Thriller. "
    "IMDB Rating: 6.8/10 Genres: Drama, Family, Thriller "
    "Director: Bathala Sateesh "
    "Stars Cast: Vinod Kumar, Meera Raaj, Inturi Vasu, Sai Simhadri "
    "Languages: Hindi + Telugu "
    "Video Quality: WEB-DL 1080p | 720p | 480p "
    "Film Story: An entitled son sues his father for neglecting his duties. "
    "The father’s powerful response sparks a courtroom battle that teaches "
    "the son about responsibility, love, and family. "
    "Watch Online Son Of 2026 WEB-DL UNCUT Hindi Dual Audio ORG Full Movie "
    "Download 1080p | 720p | 480p Free Download Full Movie Via Single Links "
    "|| 1080p Download Links: 1.7GB || DIRECT 1"
)

SON_OF_POST = {
    "id": 146083,
    "date": "2026-11-22T06:21:40",
    "slug": "son-of-2026-hindi-dual-audio-org-movie-download-webrip",
    "link": "https://bolly4u.moda/son-of-2026-hindi-dual-audio-org-movie-download-webrip/",
    "title": {"rendered": "Son Of 2026 WEB-DL UNCUT Hindi Dual Audio ORG Full Movie Download 1080p | 720p | 480p"},
    "content": {"rendered": (
        "<p><strong>" + SON_OF_TEXT.split("IMDB Rating")[0] + "</strong></p>"
        '<p><img src="https://myimg.click/images/2026/11/22/Son-Of-2026.jpg" /></p>'
        "<p><strong>IMDB Rating: 6.8/10</strong><br />"
        "<strong>Genres: Drama, Family, Thriller</strong></p>"
        "<p><strong>Director: Bathala Sateesh</strong><br />"
        "<strong>Stars Cast: Vinod Kumar, Meera Raaj, Inturi Vasu, Sai Simhadri</strong></p>"
        "<p><strong>Languages: Hindi + Telugu</strong><br />"
        "<strong>Video Quality: WEB-DL 1080p | 720p | 480p</strong></p>"
        "<p><strong>Film Story: An entitled son sues his father for neglecting "
        "his duties. The father’s powerful response sparks a courtroom battle "
        "that teaches the son about responsibility, love, and family.</strong></p>"
        "<h4><strong>|| 1080p Download Links: 1.7GB ||</strong></h4>"
        '<p><a href="https://photojin.garden/download/SAggOe4g1BE">DIRECT 1</a> '
        '<a href="https://torrentsave.0to.site/save/2f34d34dc26694d53847498e41d684aa9e617151">Download [T]</a></p>'
    )},
    "excerpt": {"rendered": (
        "<p>Son Of 2026 WEB-DL UNCUT Hindi Dual Audio ORG Full Movie Download "
        "[Hindi – Tamil ] 1080p, 720p &amp; 480p. Son Of 2026 This is a South "
        "Hindi Movie and available in Hindi 1080p, 720p &amp; 480p qualities. "
        "This is one of the best movies based on Romance, Thriller. "
        "Son Of 2026 WEB-DL Hindi Dubbed [&hellip;]</p>"
    )},
    "categories": [9910, 4, 83],
    "tags": [],
    "featured_media": 0,
    "_embedded": {
        "wp:term": [[
            {"id": 9910, "name": "720p Movies", "slug": "720p-movies",
             "taxonomy": "category"},
            {"id": 4, "name": "Telugu 300Mb", "slug": "telugu-300mb",
             "taxonomy": "category"},
            {"id": 83, "name": "Telugu 720p", "slug": "telugu-720p",
             "taxonomy": "category"},
        ]],
    },
}

# ssrmovies embeds the SITE-INDEX object (not media) under
# wp:featuredmedia — a real trap from the live 2026-11-23 sample. The
# poster must fall through to the content image, never crash or return
# the site URL.
SSR_TRAP_POST = {
    "id": 132230,
    "title": {"rendered": "The Great Indian Kapil Show S05 NF 1080p 720p 480p WEB-DL x264 ESubs [Ep 09 ADDED]"},
    "content": {"rendered": (
        '<p class="poster"><img src="https://imgurworld.top/poster.jpg" /></p>'
        "<p>IMDb: 6.5/10 || Language: Hindi (ORG 5.1) Genres: Comedy Show</p>"
    )},
    "excerpt": {"rendered": "<p>The Great Indian Kapil Show 2025 S05 NF 1080p 720p 480p WEB-DL x264 IMDb: 6.5/10</p>"},
    "categories": [2],
    "featured_media": 132000,
    "_embedded": {
        "wp:featuredmedia": [{
            "name": "SSRmovies.COM",
            "url": "https://site4.ssrmovies.page",
            "namespaces": ["wp/v2"],
            "routes": {},
        }],
        "wp:term": [[
            {"id": 2, "name": "TV Shows", "slug": "tv-shows",
             "taxonomy": "category"},
        ]],
    },
}


class TestStripAndExcerpt(unittest.TestCase):

    def test_strip_html(self):
        self.assertEqual(
            wp_rest.strip_html("<p>Hello <strong>World</strong></p>"),
            "Hello World")
        self.assertEqual(
            wp_rest.strip_html("A &amp; B &#8212; C"), "A & B — C")
        self.assertEqual(wp_rest.strip_html(""), "")

    def test_excerpt_text_strips_ellipsis(self):
        self.assertEqual(
            wp_rest.excerpt_text(SON_OF_POST),
            "Son Of 2026 WEB-DL UNCUT Hindi Dual Audio ORG Full Movie Download "
            "[Hindi – Tamil ] 1080p, 720p & 480p. Son Of 2026 This is a South "
            "Hindi Movie and available in Hindi 1080p, 720p & 480p qualities. "
            "This is one of the best movies based on Romance, Thriller. "
            "Son Of 2026 WEB-DL Hindi Dubbed")
        self.assertNotIn("[…]", wp_rest.excerpt_text(SON_OF_POST))

    def test_content_text_is_plain(self):
        text = wp_rest.content_text(SON_OF_POST)
        self.assertNotIn("<", text)
        self.assertIn("IMDB Rating: 6.8/10", text)


class TestLabelParsing(unittest.TestCase):

    def test_real_bolly4u_info_block(self):
        labels = wp_rest.parse_labelled_text(SON_OF_TEXT)
        self.assertEqual(labels["imdb_rating"], 6.8)
        self.assertEqual(labels["genres"], ["Drama", "Family", "Thriller"])
        self.assertEqual(labels["director"], "Bathala Sateesh")
        self.assertEqual(labels["cast"],
                         ["Vinod Kumar", "Meera Raaj", "Inturi Vasu",
                          "Sai Simhadri"])
        self.assertEqual(labels["languages"], "Hindi + Telugu")
        self.assertTrue(labels["story"].startswith(
            "An entitled son sues his father"))
        self.assertNotIn("Watch Online", labels["story"])
        self.assertNotIn("Download Links", labels["story"])

    def test_parse_imdb_rating_variants(self):
        self.assertEqual(wp_rest.parse_imdb_rating("IMDb: 6.5/10"), 6.5)
        self.assertEqual(wp_rest.parse_imdb_rating("IMDB Ratings: 7.0/10"), 7.0)
        self.assertEqual(wp_rest.parse_imdb_rating("IMDB Rating: 8"), 8.0)
        self.assertIsNone(wp_rest.parse_imdb_rating("No rating here"))
        self.assertIsNone(wp_rest.parse_imdb_rating("IMDb: 11/10"))
        self.assertIsNone(wp_rest.parse_imdb_rating(""))

    def test_no_labels_means_empty_not_fabricated(self):
        self.assertEqual(wp_rest.parse_labelled_text("Just a plain title."), {})
        self.assertEqual(wp_rest.parse_labelled_text(""), {})

    def test_genres_drop_quality_tokens(self):
        labels = wp_rest.parse_labelled_text("Genres: Action, 720p Movies, Drama")
        self.assertEqual(labels["genres"], ["Action", "Drama"])


class TestPosterAndGenres(unittest.TestCase):

    def test_poster_from_content_img_when_no_featured_media(self):
        # bolly4u: featured_media is 0; the poster is the content <img>.
        self.assertEqual(
            wp_rest.poster_from_post(SON_OF_POST),
            "https://myimg.click/images/2026/11/22/Son-Of-2026.jpg")

    def test_poster_from_valid_featured_media(self):
        post = {"content": {"rendered": ""},
                "_embedded": {"wp:featuredmedia": [{
                    "source_url": "https://moviezguru.host/wp-content/uploads/2026/11/poster.jpg",
                    "media_type": "image", "mime_type": "image/jpeg"}]}}
        self.assertEqual(
            wp_rest.poster_from_post(post),
            "https://moviezguru.host/wp-content/uploads/2026/11/poster.jpg")

    def test_poster_ignores_site_index_trap(self):
        # ssrmovies: wp:featuredmedia[0] is the site index, no source_url.
        self.assertEqual(
            wp_rest.poster_from_post(SSR_TRAP_POST),
            "https://imgurworld.top/poster.jpg")

    def test_poster_yoast_fallback(self):
        post = {"content": {"rendered": "<p>no image</p>"},
                "yoast_head_json": {"og_image": [
                    {"url": "https://9xmovie.cologne/wp-content/uploads/og.jpg"}]}}
        self.assertEqual(
            wp_rest.poster_from_post(post),
            "https://9xmovie.cologne/wp-content/uploads/og.jpg")

    def test_poster_none_when_nothing_carries_one(self):
        self.assertIsNone(wp_rest.poster_from_post(
            {"content": {"rendered": "<p>text only</p>"}}))

    def test_synopsis_prefers_story_label(self):
        synopsis = wp_rest.synopsis_from_post(SON_OF_POST)
        self.assertTrue(synopsis.startswith("An entitled son sues his father"))
        self.assertNotIn("Watch Online", synopsis)

    def test_synopsis_falls_back_to_excerpt(self):
        post = {"content": {"rendered": "<p>No labels here at all, just text.</p>"},
                "excerpt": {"rendered": "<p>A long enough excerpt that reads like a synopsis of the film.</p>"}}
        self.assertEqual(
            wp_rest.synopsis_from_post(post),
            "A long enough excerpt that reads like a synopsis of the film.")

    def test_genres_from_label_beats_junk_categories(self):
        # bolly4u categories are quality buckets, not genres.
        self.assertEqual(wp_rest.genres_from_post(SON_OF_POST),
                         ["Drama", "Family", "Thriller"])

    def test_genres_from_filtered_categories(self):
        post = {"content": {"rendered": "<p>No genre label here.</p>"},
                "_embedded": {"wp:term": [[
                    {"id": 1, "name": "Action", "taxonomy": "category"},
                    {"id": 2, "name": "1080p Movies", "taxonomy": "category"},
                    {"id": 3, "name": "COMPLETED", "taxonomy": "category"},
                    {"id": 4, "name": "Drama", "taxonomy": "category"},
                ]]}}
        self.assertEqual(wp_rest.genres_from_post(post), ["Action", "Drama"])

    def test_genres_from_label_even_when_categories_are_buckets(self):
        # ssrmovies: the only category is the "TV Shows" bucket, but the
        # content text carries a real "Genres: Comedy Show" label.
        self.assertEqual(wp_rest.genres_from_post(SSR_TRAP_POST),
                         ["Comedy Show"])

    def test_genres_none_when_neither_label_nor_genre_category(self):
        post = {"content": {"rendered": "<p>No labels here.</p>"},
                "_embedded": {"wp:term": [[
                    {"id": 2, "name": "TV Shows", "taxonomy": "category"},
                ]]}}
        self.assertIsNone(wp_rest.genres_from_post(post))


class TestPagination(unittest.TestCase):

    def _fake(self, pages):
        seen = []

        def get_json(url):
            seen.append(url)
            import re as _re
            m = _re.search(r"[?&]page=(\d+)", url)
            page = int(m.group(1)) if m else 1
            if page in pages:
                posts, total, total_pages = pages[page]
                return 200, posts, {"X-WP-Total": str(total),
                                    "X-WP-TotalPages": str(total_pages)}
            return 400, None, {}

        get_json.seen = seen
        return get_json

    def test_fetches_all_pages(self):
        fake = self._fake({
            1: ([{"id": 1}, {"id": 2}], 5, 3),
            2: ([{"id": 3}, {"id": 4}], 5, 3),
            3: ([{"id": 5}], 5, 3),
        })
        got = list(wp_rest.paginate_posts(fake, "https://bolly4u.moda", per_page=2))
        self.assertEqual([p for p, _, _, _ in got], [1, 2, 3])
        self.assertEqual(sum(len(posts) for _, posts, _, _ in got), 5)
        self.assertIn("_embed=1", fake.seen[0])
        self.assertIn("per_page=2", fake.seen[0])

    def test_max_pages_limits_fetch(self):
        fake = self._fake({
            1: ([{"id": 1}], 10, 5),
            2: ([{"id": 2}], 10, 5),
        })
        got = list(wp_rest.paginate_posts(fake, "https://bolly4u.moda",
                                          per_page=1, max_pages=1))
        self.assertEqual(len(got), 1)
        self.assertEqual(len(fake.seen), 1)

    def test_400_ends_pagination(self):
        fake = self._fake({1: ([{"id": 1}], 1, 1)})
        got = list(wp_rest.paginate_posts(fake, "https://bolly4u.moda", per_page=1))
        self.assertEqual(len(got), 1)

    def test_429_raises_rate_limited(self):
        def get_json(url):
            return 429, None, {}

        with self.assertRaises(wp_rest.WPReSTRateLimited):
            list(wp_rest.paginate_posts(get_json, "https://bolly4u.moda"))

    def test_500_raises(self):
        def get_json(url):
            return 500, None, {}

        with self.assertRaises(wp_rest.WPReSTError):
            list(wp_rest.paginate_posts(get_json, "https://bolly4u.moda"))

    def test_fetch_categories_pages_until_short(self):
        calls = []

        def get_json(url):
            calls.append(url)
            import re as _re
            m = _re.search(r"[?&]page=(\d+)", url)
            page = int(m.group(1)) if m else 1
            if page == 1:
                return 200, [{"id": 1, "name": "Action"},
                             {"id": 2, "name": "Drama"}], {}
            return 200, [{"id": 3, "name": "Thriller"}], {}

        cats = wp_rest.fetch_categories(get_json, "https://bolly4u.moda",
                                        per_page=2)
        self.assertEqual(cats, {1: "Action", 2: "Drama", 3: "Thriller"})
        self.assertEqual(len(calls), 2)

    def test_fetch_categories_failure_returns_empty(self):
        def get_json(url):
            return 500, None, {}

        self.assertEqual(wp_rest.fetch_categories(get_json, "https://bolly4u.moda"), {})


if __name__ == "__main__":
    unittest.main()
