package com.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.pg.common.ServiceUnavailableException;
import com.pg.location.GeoLocation;
import com.pg.location.GeocodingProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Location search over HTTP. The dev profile runs on H2, which has no PostGIS, so these tests exercise the exact
 * great-circle (haversine) engine; the PostGIS SQL differs only in the two fragments in SpatialEngine. The geocoder is
 * a mock: no test calls Google or Nominatim.
 *
 * Every test uses its own patch of the map (a different base latitude, >200 km apart) so properties made by one test
 * never appear in another test's radius.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.url=jdbc:h2:mem:staydb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;OPTIMIZE_REUSE_RESULTS=0" })
@ActiveProfiles("dev")
class StayLocationSearchTest {

	@Value("${local.server.port}")
	int port;

	@MockitoBean
	GeocodingProvider geo;

	private final HttpClient http = HttpClient.newHttpClient();
	private final ObjectMapper json = new ObjectMapper();
	private static final AtomicInteger SEQ = new AtomicInteger(100);

	private record Res(int status, JsonNode body) {
	}

	private Res call(String method, String path, String token, String body) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json");
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
		JsonNode node = r.body() == null || r.body().isBlank() ? null : json.readTree(r.body());
		return new Res(r.statusCode(), node);
	}

	private Res get(String path) throws Exception {
		return call("GET", path, null, null);
	}

	private String login(String user, String pass) throws Exception {
		Res r = call("POST", "/api/auth/login", null, "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}");
		assertThat(r.status()).as("login " + user).isEqualTo(200);
		return r.body().get("token").asString();
	}

	private static String q(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	/** A property with coordinates; returns {id, ownerToken}. */
	private record Prop(long id, String owner) {
	}

	private Prop property(String admin, String name, Double lat, Double lon, String stayType, String gender)
			throws Exception {
		int n = SEQ.incrementAndGet();
		String user = "stayowner" + n;
		String body = "{\"name\":\"" + name + "\",\"ownerUsername\":\"" + user + "\",\"ownerMobile\":\"91000" + n
				+ "00\",\"ownerPassword\":\"OwnerPass#1\"" + (lat != null ? ",\"latitude\":" + lat : "")
				+ (lon != null ? ",\"longitude\":" + lon : "")
				+ (stayType != null ? ",\"stayType\":\"" + stayType + "\"" : "")
				+ (gender != null ? ",\"genderType\":\"" + gender + "\"" : "")
				+ ",\"city\":\"Testville\",\"locality\":\"Testpur\",\"amenities\":\"Wi-Fi, Food\"}";
		Res r = call("POST", "/api/admin/pgs", admin, body);
		assertThat(r.status()).as("create " + name + ": " + r.body()).isEqualTo(200);
		return new Prop(r.body().get("id").asLong(), login(user, "OwnerPass#1"));
	}

	private static String section(String name, String sharing, int beds, int rent) {
		return "{\"sectionName\":\"" + name + "\",\"sharingType\":\"" + sharing + "\",\"capacity\":" + beds
				+ ",\"rentPerBed\":" + rent + "}";
	}

	private JsonNode room(Prop p, String number, String extra, String... sections) throws Exception {
		Res r = call("POST", "/api/rooms", p.owner(), "{\"number\":\"" + number + "\"" + extra + ",\"sections\":["
				+ String.join(",", sections) + "]}");
		assertThat(r.status()).as("room " + number + ": " + r.body()).isEqualTo(200);
		return r.body();
	}

	/** Puts a resident in the n-th bed (0-based) of the section with this name. */
	private void occupy(Prop p, JsonNode room, String sectionName, int bedIndex) throws Exception {
		for (JsonNode s : room.get("sections")) {
			if (sectionName.equals(s.get("sectionName").asString())) {
				long bedId = s.get("beds").get(bedIndex).get("id").asLong();
				int n = SEQ.incrementAndGet();
				Res r = call("POST", "/api/residents", p.owner(),
						"{\"name\":\"Tenant " + n + "\",\"phone\":\"92000" + n + "00\",\"aadhaar\":\"123456789012\",\"roomId\":" + room.get("id").asLong()
								+ ",\"sectionId\":" + s.get("id").asLong() + ",\"bedId\":" + bedId + ",\"joinedOn\":\""
								+ LocalDate.now() + "\"}");
				assertThat(r.status()).as("occupy: " + r.body()).isEqualTo(200);
				return;
			}
		}
		throw new AssertionError("no section " + sectionName);
	}

	private static List<Long> ids(JsonNode page) {
		List<Long> out = new ArrayList<>();
		for (JsonNode c : page.get("content")) {
			out.add(c.get("propertyId").asLong());
		}
		return out;
	}

	private String nearby(double lat, double lon, String more) {
		return "/api/stays/nearby?latitude=" + lat + "&longitude=" + lon + (more == null ? "" : "&" + more);
	}

	private JsonNode card(JsonNode page, long id) {
		for (JsonNode c : page.get("content")) {
			if (c.get("propertyId").asLong() == id) {
				return c;
			}
		}
		throw new AssertionError("property " + id + " not in " + ids(page));
	}

	private JsonNode option(JsonNode card, String sharingType) {
		for (JsonNode o : card.get("sharingOptions")) {
			if (sharingType.equals(o.get("sharingType").asString())) {
				return o;
			}
		}
		return null;
	}

	// ---- 1, 2, 12: radius, nothing nearby, distance ----

	@Test
	void radiusKeepsOnlyNearbyStaysNearestFirstAndReportsDistance() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		double lat = 10.0, lon = 77.0;
		Prop near = property(admin, "Near PG", lat + 0.005, lon, "PG", "CO_LIVING"); // ~0.56 km
		Prop mid = property(admin, "Mid PG", lat + 0.03, lon, "PG", "CO_LIVING"); // ~3.3 km
		Prop far = property(admin, "Far PG", lat + 0.07, lon, "PG", "CO_LIVING"); // ~7.8 km
		property(admin, "Elsewhere PG", 19.07, 72.87, "PG", "CO_LIVING"); // another city
		for (Prop p : List.of(far, near, mid)) {
			room(p, "1", "", section("A", "TWO_SHARING", 2, 5000));
		}

		assertThat(ids(get(nearby(lat, lon, "radiusKm=1")).body())).containsExactly(near.id());
		assertThat(ids(get(nearby(lat, lon, "radiusKm=5")).body())).containsExactly(near.id(), mid.id());
		JsonNode all = get(nearby(lat, lon, "radiusKm=10")).body();
		assertThat(ids(all)).containsExactly(near.id(), mid.id(), far.id());
		assertThat(card(all, near.id()).get("distanceKm").asDouble()).isBetween(0.5, 0.6);
		assertThat(card(all, mid.id()).get("distanceKm").asDouble()).isBetween(3.2, 3.5);
		assertThat(all.get("totalElements").asInt()).isEqualTo(3);
		// default radius is 5 km
		assertThat(ids(get(nearby(lat, lon, null)).body())).containsExactly(near.id(), mid.id());
		// Search needs no login.
		assertThat(get(nearby(lat, lon, "radiusKm=1")).status()).isEqualTo(200);
	}

	@Test
	void searchWithNothingNearbyReturnsAnEmptyPageNotAnError() throws Exception {
		Res r = get(nearby(12.0, 80.0, "radiusKm=5"));
		assertThat(r.status()).isEqualTo(200);
		assertThat(r.body().get("content").size()).isZero();
		assertThat(r.body().get("totalElements").asInt()).isZero();
		assertThat(r.body().get("totalPages").asInt()).isZero();
	}

	// ---- 4, 5: locality search, geocoding, cache, failures ----

	@Test
	void searchByLocalityResolvesOnceThenServesFromCacheAndSkipsGeocoderWhenCoordinatesAreKnown() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		double lat = 14.0, lon = 77.0;
		Prop p = property(admin, "Locality PG", lat + 0.004, lon, "PG", "CO_LIVING");
		room(p, "1", "", section("A", "PRIVATE", 1, 8000));
		when(geo.search(eq("marathahalli test"), anyInt())).thenReturn(List.of(new GeoLocation(
				"Marathahalli, Bengaluru, Karnataka, India", lat, lon, "Marathahalli", null, "Bengaluru", "Karnataka",
				"560037", "India")));

		Res first = get("/api/stays/search?query=" + q("Marathahalli Test") + "&radiusKm=2");
		assertThat(first.status()).isEqualTo(200);
		assertThat(first.body().get("location").get("locality").asString()).isEqualTo("Marathahalli");
		assertThat(first.body().get("location").get("pincode").asString()).isEqualTo("560037");
		assertThat(ids(first.body().get("results"))).containsExactly(p.id());

		// Same place, different spelling of case/spaces: the provider is not asked again.
		assertThat(get("/api/stays/search?query=" + q("  marathahalli   TEST ")).status()).isEqualTo(200);
		assertThat(get("/api/locations/suggestions?query=" + q("Marathahalli Test")).body().get(0).get("city").asString())
				.isEqualTo("Bengaluru");
		verify(geo, times(1)).search(eq("marathahalli test"), anyInt());

		// The client already has coordinates from a picked suggestion: no geocoding at all.
		Res withCoords = get("/api/stays/search?query=" + q("Some Picked Place") + "&latitude=" + lat + "&longitude="
				+ lon + "&radiusKm=2");
		assertThat(ids(withCoords.body().get("results"))).containsExactly(p.id());
		verify(geo, never()).search(eq("some picked place"), anyInt());
	}

	@Test
	void geocodingFailuresAreReportedClearly() throws Exception {
		when(geo.search(eq("nowhereland"), anyInt())).thenReturn(List.of());
		Res none = get("/api/stays/search?query=nowhereland");
		assertThat(none.status()).isEqualTo(404);
		assertThat(none.body().get("message").asString()).contains("nowhereland");
		verify(geo, times(1)).search(eq("nowhereland"), anyInt());
		get("/api/stays/search?query=nowhereland"); // remembered as unknown: no second upstream call
		verify(geo, times(1)).search(eq("nowhereland"), anyInt());

		when(geo.search(eq("provider down"), anyInt())).thenThrow(new ServiceUnavailableException("busy"));
		Res down = get("/api/stays/search?query=" + q("provider down"));
		assertThat(down.status()).isEqualTo(503);
		assertThat(down.body().get("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
		// A failure is not cached: once the provider recovers the same query works.
		when(geo.search(eq("provider down"), anyInt())).thenReturn(List.of(new GeoLocation("Back, India", 16.0, 77.0,
				null, null, null, null, null, null)));
		assertThat(get("/api/stays/search?query=" + q("provider down")).status()).isEqualTo(200);

		assertThat(get("/api/stays/search?query=ab").status()).as("too short").isEqualTo(400);
		assertThat(get("/api/stays/search").status()).as("no query").isEqualTo(400);
		assertThat(get("/api/locations/suggestions?query=" + q("x".repeat(200))).status()).isEqualTo(400);
		verify(geo, never()).search(eq("ab"), anyInt());
		verify(geo, never()).search(eq(""), anyInt());
		when(geo.search(anyString(), anyInt())).thenReturn(List.of());
	}

	// ---- 6, 7: GPS denied / invalid input ----

	@Test
	void missingOrInvalidCoordinatesAndLimitsAreRejected() throws Exception {
		// GPS denied: the app sends no coordinates -> a clear 400 it can answer by asking for a locality instead.
		Res noGps = get("/api/stays/nearby?radiusKm=5");
		assertThat(noGps.status()).isEqualTo(400);
		assertThat(noGps.body().get("message").asString()).contains("latitude and longitude");
		assertThat(get("/api/stays/nearby?latitude=12.9").status()).isEqualTo(400);

		assertThat(get(nearby(91, 77, null)).status()).as("latitude > 90").isEqualTo(400);
		assertThat(get(nearby(-91, 77, null)).status()).isEqualTo(400);
		assertThat(get(nearby(12, 181, null)).status()).as("longitude > 180").isEqualTo(400);
		assertThat(get(nearby(12, -181, null)).status()).isEqualTo(400);
		assertThat(get("/api/stays/nearby?latitude=abc&longitude=77").status()).isEqualTo(400);

		assertThat(get(nearby(12, 77, "radiusKm=0")).status()).isEqualTo(400);
		assertThat(get(nearby(12, 77, "radiusKm=-3")).status()).isEqualTo(400);
		assertThat(get(nearby(12, 77, "radiusKm=500")).status()).as("max radius").isEqualTo(400);
		assertThat(get(nearby(12, 77, "size=1000")).status()).as("max page size").isEqualTo(400);
		assertThat(get(nearby(12, 77, "size=0")).status()).isEqualTo(400);
		assertThat(get(nearby(12, 77, "page=-1")).status()).isEqualTo(400);
		assertThat(get(nearby(12, 77, "minRent=9000&maxRent=1000")).status()).isEqualTo(400);
		assertThat(get(nearby(12, 77, "minRent=-5")).status()).isEqualTo(400);
		assertThat(get(nearby(12, 77, "sharingType=HUGE")).status()).isEqualTo(400);
		assertThat(get(nearby(12, 77, "sortBy=BEST")).status()).isEqualTo(400);
		assertThat(get(nearby(12, 77, "gender=ROBOT")).status()).isEqualTo(400);
		assertThat(get(nearby(12, 77, "radiusKm=50&size=50")).status()).as("limits are inclusive").isEqualTo(200);
	}

	@Test
	void propertyLocationIsValidatedWhenSavedAndOwnersCanSetItOnTheirOwnProperty() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		int n = SEQ.incrementAndGet();
		String base = "\"ownerUsername\":\"locowner" + n + "\",\"ownerMobile\":\"93000" + n
				+ "00\",\"ownerPassword\":\"OwnerPass#1\"";
		assertThat(call("POST", "/api/admin/pgs", admin, "{\"name\":\"Bad Lat\"," + base + ",\"latitude\":95,\"longitude\":77}")
				.status()).isEqualTo(400);
		assertThat(call("POST", "/api/admin/pgs", admin, "{\"name\":\"Bad Lon\"," + base + ",\"latitude\":12,\"longitude\":200}")
				.status()).isEqualTo(400);
		assertThat(call("POST", "/api/admin/pgs", admin, "{\"name\":\"Half\"," + base + ",\"latitude\":12}").status())
				.as("latitude without longitude").isEqualTo(400);

		Prop p = property(admin, "Own Location PG", null, null, "PG", "CO_LIVING"); // no coordinates yet
		room(p, "1", "", section("A", "PRIVATE", 1, 7000));
		assertThat(ids(get(nearby(18.0, 77.0, "radiusKm=5")).body())).as("no coordinates -> not searchable").isEmpty();

		Res saved = call("PUT", "/api/pg/profile", p.owner(),
				"{\"name\":\"Own Location PG\",\"latitude\":18.001,\"longitude\":77.0,\"locality\":\"Hitech\","
						+ "\"pincode\":\"500081\"}");
		assertThat(saved.status()).isEqualTo(200);
		assertThat(saved.body().get("latitude").asDouble()).isEqualTo(18.001);
		assertThat(saved.body().get("pincode").asString()).isEqualTo("500081");
		assertThat(ids(get(nearby(18.0, 77.0, "radiusKm=5")).body())).containsExactly(p.id());

		// Another save without coordinates keeps them; a bad one is refused and changes nothing.
		assertThat(call("PUT", "/api/pg/profile", p.owner(), "{\"name\":\"Own Location PG\",\"city\":\"Hyd\"}").body()
				.get("latitude").asDouble()).isEqualTo(18.001);
		assertThat(call("PUT", "/api/pg/profile", p.owner(), "{\"name\":\"Own Location PG\",\"latitude\":-100,\"longitude\":0}")
				.status()).isEqualTo(400);
		assertThat(ids(get(nearby(18.0, 77.0, "radiusKm=5")).body())).containsExactly(p.id());
		// The owner of another property cannot move this one: the profile API only edits the caller's own property.
		Prop other = property(admin, "Not Mine", null, null, "PG", "CO_LIVING");
		call("PUT", "/api/pg/profile", other.owner(), "{\"name\":\"Not Mine\",\"latitude\":18.0005,\"longitude\":77.0}");
		assertThat(get("/api/stays/" + p.id()).body().get("latitude").asDouble()).isEqualTo(18.001);
	}

	// ---- 8, 11: availability with occupied and vacant beds ----

	@Test
	void onlyStaysWithABookableBedAreReturnedAndOccupiedBedsAreNotCounted() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		double lat = 16.0, lon = 77.0;
		Prop full = property(admin, "Full PG", lat + 0.002, lon, "PG", "CO_LIVING");
		Prop partly = property(admin, "Partly PG", lat + 0.004, lon, "PG", "CO_LIVING");
		JsonNode fullRoom = room(full, "1", "", section("A", "TWO_SHARING", 2, 5000));
		JsonNode partRoom = room(partly, "1", "", section("A", "THREE_SHARING", 3, 4000));
		occupy(full, fullRoom, "A", 0);
		occupy(full, fullRoom, "A", 1);
		occupy(partly, partRoom, "A", 0);

		JsonNode available = get(nearby(lat, lon, "radiusKm=3")).body();
		assertThat(ids(available)).as("fully occupied property is hidden").containsExactly(partly.id());
		assertThat(card(available, partly.id()).get("availableBeds").asInt()).isEqualTo(2);
		assertThat(option(card(available, partly.id()), "THREE_SHARING").get("availableBeds").asInt()).isEqualTo(2);

		JsonNode everything = get(nearby(lat, lon, "radiusKm=3&availableOnly=false")).body();
		assertThat(ids(everything)).containsExactly(full.id(), partly.id());
		assertThat(card(everything, full.id()).get("availableBeds").asInt()).isZero();
		assertThat(card(everything, partly.id()).get("availableBeds").asInt()).isEqualTo(2);

		// Occupy the last free beds: the second property drops out too.
		occupy(partly, partRoom, "A", 1);
		occupy(partly, partRoom, "A", 2);
		assertThat(ids(get(nearby(lat, lon, "radiusKm=3")).body())).isEmpty();
	}

	@Test
	void aBedFreeingUpOnAnApprovedNoticeKeepsTheStayVisibleWithTheDateButIsNotCountedAsAvailableNow() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		double lat = 21.0, lon = 77.0;
		Prop soon = property(admin, "Leaving Soon PG", lat + 0.002, lon, "PG", "CO_LIVING");
		JsonNode r = room(soon, "1", "", section("A", "PRIVATE", 1, 7000));
		long bedId = r.get("sections").get(0).get("beds").get(0).get("id").asLong();
		int n = SEQ.incrementAndGet();
		Res res = call("POST", "/api/residents", soon.owner(), "{\"name\":\"Leaver " + n + "\",\"phone\":\"93000" + n
				+ "00\",\"aadhaar\":\"123456789012\",\"roomId\":" + r.get("id").asLong() + ",\"sectionId\":" + r.get("sections").get(0).get("id").asLong()
				+ ",\"bedId\":" + bedId + ",\"joinedOn\":\"" + LocalDate.now() + "\"}");
		assertThat(res.status()).isEqualTo(200);
		long residentId = res.body().get("id").asLong();
		String resident = login("93000" + n + "00", "9012");
		String leaveOn = LocalDate.now().plusDays(40).toString();

		// Not yet approved: still just "full".
		assertThat(call("POST", "/api/me/vacate-notice", resident, "{\"vacateOn\":\"" + leaveOn + "\"}").status()).isEqualTo(200);
		assertThat(ids(get(nearby(lat, lon, "radiusKm=3")).body())).isEmpty();
		JsonNode before = card(get(nearby(lat, lon, "radiusKm=3&availableOnly=false")).body(), soon.id());
		assertThat(before.get("upcomingBeds").asInt()).isZero();

		// Approved: shown with the date, but not counted as available today.
		assertThat(call("POST", "/api/residents/" + residentId + "/notice/approve", soon.owner(), null).status()).isEqualTo(200);
		JsonNode page = get(nearby(lat, lon, "radiusKm=3")).body();
		JsonNode c = card(page, soon.id());
		assertThat(c.get("availableBeds").asInt()).isZero();
		assertThat(c.get("upcomingBeds").asInt()).isEqualTo(1);
		assertThat(c.get("availableFrom").asString()).isEqualTo(leaveOn);
		JsonNode o = option(c, "PRIVATE");
		assertThat(o.get("availableBeds").asInt()).isZero();
		assertThat(o.get("upcomingBeds").asInt()).isEqualTo(1);
		assertThat(o.get("availableFrom").asString()).isEqualTo(leaveOn);

		// The plain browse list reports it too.
		JsonNode listed = null;
		for (JsonNode p : get("/api/public/pgs").body()) {
			if (p.get("id").asLong() == soon.id()) {
				listed = p;
			}
		}
		assertThat(listed).isNotNull();
		assertThat(listed.get("vacantBeds").asInt()).isZero();
		assertThat(listed.get("upcomingBeds").asInt()).isEqualTo(1);
		assertThat(listed.get("availableFrom").asString()).isEqualTo(leaveOn);

		// Withdrawn: back to full.
		assertThat(call("DELETE", "/api/me/vacate-notice", resident, null).status()).isEqualTo(200);
		assertThat(ids(get(nearby(lat, lon, "radiusKm=3")).body())).isEmpty();
	}

	// ---- 9, 10: sharing, rent, several sections in one room ----

	@Test
	void sharingTypeRentAndSectionFiltersWorkPerBedAcrossSectionsOfOneRoom() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		double lat = 18.5, lon = 77.0;
		// Room 101 has three sections, as in a real PG: 3 sharing, 2 sharing and a single bed.
		Prop multi = property(admin, "Multi Section PG", lat + 0.002, lon, "PG", "CO_LIVING");
		JsonNode r101 = room(multi, "101", "", section("A", "THREE_SHARING", 3, 4000), section("B", "TWO_SHARING", 2, 6000),
				section("C", "PRIVATE", 1, 9000));
		Prop duo = property(admin, "Duo PG", lat + 0.004, lon, "PG", "CO_LIVING");
		room(duo, "1", ",\"acType\":\"AC\"", section("X", "TWO_SHARING", 2, 5500));
		String near = "radiusKm=3";

		JsonNode all = get(nearby(lat, lon, near)).body();
		assertThat(ids(all)).containsExactly(multi.id(), duo.id());
		JsonNode m = card(all, multi.id());
		assertThat(m.get("availableBeds").asInt()).isEqualTo(6);
		assertThat(m.get("startingRent").asInt()).isEqualTo(4000);
		assertThat(m.get("maxRent").asInt()).isEqualTo(9000);
		assertThat(m.get("sharingOptions").size()).isEqualTo(3);
		assertThat(option(m, "THREE_SHARING").get("availableBeds").asInt()).isEqualTo(3);
		assertThat(option(m, "TWO_SHARING").get("rentPerMonth").asInt()).isEqualTo(6000);
		assertThat(option(m, "PRIVATE").get("label").asString()).isEqualTo("Single");
		assertThat(option(m, "PRIVATE").get("availableBeds").asInt()).isEqualTo(1);

		// Sharing type: the words people use (DOUBLE) and the enum both work; only matching sections are reported.
		JsonNode doubles = get(nearby(lat, lon, near + "&sharingType=DOUBLE")).body();
		assertThat(ids(doubles)).containsExactly(multi.id(), duo.id());
		assertThat(card(doubles, multi.id()).get("sharingOptions").size()).isEqualTo(1);
		assertThat(card(doubles, multi.id()).get("startingRent").asInt()).isEqualTo(6000);
		assertThat(card(doubles, multi.id()).get("availableBeds").asInt()).isEqualTo(2);
		JsonNode singles = get(nearby(lat, lon, near + "&sharingType=SINGLE")).body();
		assertThat(ids(singles)).containsExactly(multi.id());
		assertThat(ids(get(nearby(lat, lon, near + "&sharingType=THREE_SHARING")).body())).containsExactly(multi.id());
		assertThat(ids(get(nearby(lat, lon, near + "&sharingType=FOUR_SHARING")).body())).isEmpty();

		// Rent range is applied to each bed, so it can match one section of a room and not the others.
		JsonNode min5 = get(nearby(lat, lon, near + "&minRent=5000")).body();
		assertThat(card(min5, multi.id()).get("startingRent").asInt()).as("section A (4000) excluded").isEqualTo(6000);
		assertThat(card(min5, multi.id()).get("sharingOptions").size()).isEqualTo(2);
		JsonNode max4500 = get(nearby(lat, lon, near + "&maxRent=4500")).body();
		assertThat(ids(max4500)).as("Duo PG has nothing under 4500").containsExactly(multi.id());
		assertThat(card(max4500, multi.id()).get("maxRent").asInt()).isEqualTo(4000);
		JsonNode band = get(nearby(lat, lon, near + "&minRent=5000&maxRent=7000")).body();
		assertThat(ids(band)).containsExactly(multi.id(), duo.id());
		assertThat(card(band, multi.id()).get("sharingOptions").size()).isEqualTo(1);
		assertThat(ids(get(nearby(lat, lon, near + "&minRent=10000")).body())).isEmpty();

		// Occupied beds of one section are not offered or priced.
		occupy(multi, r101, "A", 0);
		occupy(multi, r101, "A", 1);
		occupy(multi, r101, "A", 2);
		JsonNode afterA = get(nearby(lat, lon, near)).body();
		assertThat(card(afterA, multi.id()).get("availableBeds").asInt()).isEqualTo(3);
		assertThat(option(card(afterA, multi.id()), "THREE_SHARING")).as("section A is full").isNull();
		assertThat(card(afterA, multi.id()).get("startingRent").asInt()).isEqualTo(6000);
		occupy(multi, r101, "B", 0);
		assertThat(option(card(get(nearby(lat, lon, near)).body(), multi.id()), "TWO_SHARING").get("availableBeds").asInt())
				.isEqualTo(1);

		// AC / attached bathroom come from the section, else the room.
		assertThat(ids(get(nearby(lat, lon, near + "&acType=AC")).body())).containsExactly(duo.id());
		assertThat(ids(get(nearby(lat, lon, near + "&acType=NON_AC")).body())).containsExactly(multi.id());
		assertThat(ids(get(nearby(lat, lon, near + "&attachedBathroom=true")).body())).isEmpty();
	}

	@Test
	void stayTypeGenderAndAmenityFilters() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		double lat = 20.5, lon = 77.0;
		Prop gents = property(admin, "Gents PG", lat + 0.001, lon, "PG", "GENTS");
		Prop ladies = property(admin, "Ladies Hostel", lat + 0.002, lon, "HOSTEL", "LADIES");
		Prop mixed = property(admin, "Mixed Flat", lat + 0.003, lon, "APARTMENT", "CO_LIVING");
		for (Prop p : List.of(gents, ladies, mixed)) {
			room(p, "1", "", section("A", "TWO_SHARING", 2, 5000));
		}
		String near = "radiusKm=2";
		assertThat(ids(get(nearby(lat, lon, near + "&stayType=hostel")).body())).containsExactly(ladies.id());
		assertThat(ids(get(nearby(lat, lon, near + "&stayType=APARTMENT")).body())).containsExactly(mixed.id());
		assertThat(ids(get(nearby(lat, lon, near + "&gender=MALE")).body())).containsExactly(gents.id(), mixed.id());
		assertThat(ids(get(nearby(lat, lon, near + "&gender=LADIES")).body())).containsExactly(ladies.id(), mixed.id());
		assertThat(ids(get(nearby(lat, lon, near + "&gender=ALL")).body())).hasSize(3);
		assertThat(ids(get(nearby(lat, lon, near + "&amenities=wi-fi")).body())).hasSize(3);
		assertThat(ids(get(nearby(lat, lon, near + "&amenities=gym")).body())).isEmpty();
		// LIKE wildcards in user input are not wildcards: "%" alone matches nothing special.
		assertThat(ids(get(nearby(lat, lon, near + "&amenities=" + q("%"))).body())).hasSize(3);
	}

	// ---- 13: pagination & sorting ----

	@Test
	void paginationAndSortingNeverRepeatAPropertyEvenWithManySectionsAndBeds() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		double lat = 22.5, lon = 77.0;
		List<Prop> props = new ArrayList<>();
		int[] rents = { 7000, 3000, 9000, 5000, 4000 };
		for (int i = 0; i < 5; i++) {
			Prop p = property(admin, "Page PG " + i, lat + 0.004 * (i + 1), lon, "PG", "CO_LIVING"); // 0.44 km steps
			// Two rooms x two sections x several beds: a naive join would repeat each property many times.
			room(p, "1", "", section("A", "TWO_SHARING", 2, rents[i]), section("B", "THREE_SHARING", 3, rents[i] + 500));
			room(p, "2", "", section("A", "TWO_SHARING", 2, rents[i]), section("B", "PRIVATE", 1, rents[i] + 1000));
			props.add(p);
		}
		String near = "radiusKm=5&size=2&sortBy=NEAREST";
		List<Long> seen = new ArrayList<>();
		for (int page = 0; page < 3; page++) {
			JsonNode r = get(nearby(lat, lon, near + "&page=" + page)).body();
			assertThat(r.get("totalElements").asInt()).isEqualTo(5);
			assertThat(r.get("totalPages").asInt()).isEqualTo(3);
			assertThat(r.get("page").asInt()).isEqualTo(page);
			assertThat(r.get("size").asInt()).isEqualTo(2);
			assertThat(r.get("content").size()).isEqualTo(page < 2 ? 2 : 1);
			seen.addAll(ids(r));
		}
		assertThat(seen).containsExactlyElementsOf(props.stream().map(Prop::id).toList());
		assertThat(get(nearby(lat, lon, near + "&page=3")).body().get("content").size()).as("past the end").isZero();

		JsonNode first = get(nearby(lat, lon, "radiusKm=5&size=5")).body();
		double previous = -1;
		for (JsonNode c : first.get("content")) {
			assertThat(c.get("distanceKm").asDouble()).isGreaterThan(previous);
			previous = c.get("distanceKm").asDouble();
		}
		assertThat(card(first, props.get(0).id()).get("availableBeds").asInt()).as("2+3+2+1 beds, counted once").isEqualTo(8);

		List<Long> cheapest = ids(get(nearby(lat, lon, "radiusKm=5&sortBy=RENT_ASC")).body());
		assertThat(cheapest).containsExactly(props.get(1).id(), props.get(4).id(), props.get(3).id(), props.get(0).id(),
				props.get(2).id());
		List<Long> dearest = ids(get(nearby(lat, lon, "radiusKm=5&sortBy=RENT_DESC")).body());
		assertThat(dearest.get(0)).isEqualTo(props.get(2).id());
		assertThat(dearest.get(4)).isEqualTo(props.get(1).id());
	}

	// ---- 3: visibility ----

	@Test
	void switchedOffPropertiesAreHiddenAndNoPaymentDetailsAreExposed() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		double lat = 24.5, lon = 77.0;
		Prop live = property(admin, "Live PG", lat + 0.001, lon, "PG", "CO_LIVING");
		Prop off = property(admin, "Switched Off PG", lat + 0.002, lon, "PG", "CO_LIVING");
		for (Prop p : List.of(live, off)) {
			room(p, "1", "", section("A", "TWO_SHARING", 2, 5000));
		}
		assertThat(call("PUT", "/api/pg/profile", live.owner(),
				"{\"name\":\"Live PG\",\"upiId\":\"secret@upi\",\"upiName\":\"Secret Name\"}").status()).isEqualTo(200);

		assertThat(ids(get(nearby(lat, lon, "radiusKm=2")).body())).containsExactly(live.id(), off.id());
		assertThat(call("PUT", "/api/admin/pgs/" + off.id() + "/status?active=false", admin, null).status()).isEqualTo(200);
		JsonNode visible = get(nearby(lat, lon, "radiusKm=2")).body();
		assertThat(ids(visible)).containsExactly(live.id());
		assertThat(get("/api/stays/" + off.id()).status()).as("detail of a hidden property").isEqualTo(404);
		assertThat(get("/api/stays/987654").status()).isEqualTo(404);

		// Residents never see the owner's payment details or login data.
		String card = visible.get("content").get(0).toString();
		assertThat(card).doesNotContain("upi").doesNotContain("secret@upi").doesNotContain("licenseKey")
				.doesNotContain("ownerName");
		JsonNode detail = get("/api/stays/" + live.id()).body();
		assertThat(detail.get("property").get("upiId").isNull()).isTrue();
		assertThat(detail.get("property").get("ownerName").isNull()).isTrue();
		assertThat(detail.get("property").get("email").isNull()).isTrue();
		assertThat(detail.toString()).doesNotContain("secret@upi").doesNotContain("Secret Name");
		assertThat(detail.get("availableBeds").asInt()).isEqualTo(2);
		assertThat(detail.get("startingRent").asInt()).isEqualTo(5000);
		assertThat(detail.get("sharingOptions").get(0).get("sharingType").asString()).isEqualTo("TWO_SHARING");
		assertThat(detail.get("property").get("amenities").size()).isEqualTo(2);

		assertThat(call("PUT", "/api/admin/pgs/" + off.id() + "/status?active=true", admin, null).status()).isEqualTo(200);
		assertThat(ids(get(nearby(lat, lon, "radiusKm=2")).body())).containsExactly(live.id(), off.id());
	}

	@Test
	void searchRoutesAreReadOnlyAndOwnerAreasStayProtected() throws Exception {
		assertThat(call("POST", "/api/stays/nearby", null, "{}").status()).isIn(401, 403);
		assertThat(call("DELETE", "/api/stays/1", null, null).status()).isIn(401, 403);
		assertThat(get("/api/rooms").status()).isEqualTo(401);
		assertThat(get("/api/residents").status()).isEqualTo(401);
	}
}
