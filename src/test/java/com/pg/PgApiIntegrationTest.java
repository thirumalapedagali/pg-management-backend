package com.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;

import com.pg.attachment.CloudinaryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class PgApiIntegrationTest {

	@Value("${local.server.port}")
	int port;

	/** Files go to Cloudinary in production; the tests never call it. */
	@MockitoBean
	CloudinaryService cloud;

	private final HttpClient http = HttpClient.newHttpClient();
	private final ObjectMapper json = new ObjectMapper();

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

	private String login(String user, String pass) throws Exception {
		Res r = call("POST", "/api/auth/login", null, "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}");
		assertThat(r.status()).as("login " + user).isEqualTo(200);
		return r.body().get("token").asString();
	}

	private long createPg(String admin, String name, String ownerUser, String ownerMobile) throws Exception {
		Res r = call("POST", "/api/admin/pgs", admin, "{\"name\":\"" + name + "\",\"ownerUsername\":\"" + ownerUser
				+ "\",\"ownerMobile\":\"" + ownerMobile + "\",\"ownerPassword\":\"OwnerPass#1\"}");
		assertThat(r.status()).isEqualTo(200);
		return r.body().get("id").asLong();
	}

	@Test
	void fullFlow() throws Exception {
		// --- auth basics ---
		assertThat(call("GET", "/api/rooms", null, null).status()).isEqualTo(401);
		assertThat(call("GET", "/api/rooms", "garbage.token.value", null).status()).isEqualTo(401);
		assertThat(call("POST", "/api/auth/login", null, "{\"username\":\"superadmin\",\"password\":\"wrong\"}").status())
				.isEqualTo(401);
		// a stale token on a public route must not break login
		assertThat(call("POST", "/api/auth/login", "garbage", "{\"username\":\"superadmin\",\"password\":\"DevAdmin#123\"}")
				.status()).isEqualTo(200);

		String admin = login("superadmin", "DevAdmin#123");
		Res adminLogin = call("POST", "/api/auth/login", null, "{\"username\":\"superadmin\",\"password\":\"DevAdmin#123\"}");
		assertThat(adminLogin.body().get("requiresPgSelection").asBoolean()).isTrue();

		// --- two tenants ---
		createPg(admin, "Sunrise PG", "ownerA", "9000000001");
		createPg(admin, "Moonlight PG", "ownerB", "9000000002");
		String a = login("ownerA", "OwnerPass#1");
		String b = login("ownerB", "OwnerPass#1");

		// owners cannot use admin APIs
		assertThat(call("GET", "/api/admin/pgs", a, null).status()).isEqualTo(403);

		// --- rooms, capacity ---
		Res room = call("POST", "/api/rooms", a, "{\"number\":\"101\",\"capacity\":1,\"rent\":5000}");
		assertThat(room.status()).isEqualTo(200);
		long roomId = room.body().get("id").asLong();
		assertThat(call("POST", "/api/rooms", a, "{\"number\":\"101\",\"capacity\":2,\"rent\":1}").status()).isEqualTo(400);

		String today = LocalDate.now().toString();
		Res res1 = call("POST", "/api/residents", a,
				"{\"name\":\"Asha\",\"phone\":\"9876543210\",\"aadhaar\":\"123456789012\",\"roomId\":" + roomId + ",\"joinedOn\":\"" + today + "\"}");
		assertThat(res1.status()).isEqualTo(200);
		long residentId = res1.body().get("id").asLong();
		assertThat(res1.body().get("due").decimalValue().intValue()).isEqualTo(5000);
		assertThat(call("POST", "/api/residents", a, "{\"name\":\"Ravi\",\"phone\":\"9876543211\",\"aadhaar\":\"123456789012\",\"roomId\":" + roomId + "}").status())
				.as("room is full").isEqualTo(400);

		// --- tenant isolation & global resident uniqueness by mobile ---
		assertThat(call("GET", "/api/rooms", b, null).body().size()).isZero();
		assertThat(call("GET", "/api/residents/" + residentId, b, null).status()).isEqualTo(404);
		assertThat(call("POST", "/api/residents", b, "{\"name\":\"X\",\"phone\":\"9876543212\",\"aadhaar\":\"123456789012\",\"roomId\":" + roomId + "}").status())
				.as("cannot use another PG's room").isEqualTo(400);

		Res roomB = call("POST", "/api/rooms", b, "{\"number\":\"201\",\"capacity\":2,\"rent\":7000}");
		assertThat(roomB.status()).isEqualTo(200);
		long roomBId = roomB.body().get("id").asLong();

		// Resident Asha (9876543210) is already active in PG A, cannot be allocated in PG B or anywhere else
		assertThat(call("POST", "/api/residents", b,
				"{\"name\":\"Asha\",\"phone\":\"9876543210\",\"aadhaar\":\"123456789012\",\"roomId\":" + roomBId + "}").status())
				.as("resident phone must be unique across all PGs").isEqualTo(400);

		assertThat(call("POST", "/api/payments", b, "{\"residentId\":" + residentId + ",\"amount\":10}").status())
				.isEqualTo(400);

		// --- payments, dues ---
		assertThat(call("POST", "/api/payments", a, "{\"residentId\":" + residentId + ",\"amount\":-5}").status())
				.isEqualTo(400);
		assertThat(call("POST", "/api/payments", a, "{\"residentId\":" + residentId + ",\"amount\":2000}").status())
				.isEqualTo(200);
		assertThat(call("GET", "/api/residents/" + residentId, a, null).body().get("due").decimalValue().intValue())
				.isEqualTo(3000);

		// --- move resident to another room ---
		Res room2 = call("POST", "/api/rooms", a, "{\"number\":\"102\",\"capacity\":1,\"rent\":6000}");
		assertThat(room2.status()).isEqualTo(200);
		long room2Id = room2.body().get("id").asLong();

		Res moveRes = call("POST", "/api/residents/" + residentId + "/move", a, "{\"roomId\":" + room2Id + "}");
		assertThat(moveRes.status()).isEqualTo(200);
		assertThat(moveRes.body().get("roomNumber").asString()).isEqualTo("102");

		// Room 101 should now be vacant and accept a new resident
		Res newResInRoom1 = call("POST", "/api/residents", a, "{\"name\":\"Ravi\",\"phone\":\"9876543213\",\"aadhaar\":\"123456789012\",\"roomId\":" + roomId + "}");
		assertThat(newResInRoom1.status()).isEqualTo(200);

		// --- expenses ---
		assertThat(call("POST", "/api/expenses", a,
				"{\"title\":\"Power bill\",\"category\":\"Electricity\",\"amount\":1200.50}").status()).isEqualTo(200);
		Res list = call("GET", "/api/expenses?q=power&category=Electricity", a, null);
		assertThat(list.body().get("totalElements").asLong()).isEqualTo(1);
		assertThat(call("GET", "/api/expenses?q=%25", a, null).body().get("totalElements").asLong())
				.as("% is literal, not a wildcard").isZero();
		Res sum = call("GET", "/api/expenses/summary", a, null);
		assertThat(sum.body().get("today").decimalValue().doubleValue()).isEqualTo(1200.50);
		assertThat(call("GET", "/api/master/expenses", a, null).body().size()).isGreaterThan(3);

		// --- dashboard ---
		Res dash = call("GET", "/api/dashboard/summary", a, null);
		assertThat(dash.body().get("collections").get("total").decimalValue().intValue()).isEqualTo(2000);
		assertThat(dash.body().get("net").get("total").decimalValue().doubleValue()).isEqualTo(799.50);
		assertThat(dash.body().get("beds").asInt()).isEqualTo(2);
		assertThat(dash.body().get("vacant").asInt()).isZero();
		assertThat(dash.body().get("months").size()).isEqualTo(6);
		assertThat(call("GET", "/api/dashboard/today", a, null).body().get("payments").size()).isEqualTo(1);

		// --- resident login & role limits ---
		// Creating the resident already created the login: username = mobile, password = last 4 of the Aadhaar.
		JsonNode ru = null;
		for (JsonNode u : call("GET", "/api/users", a, null).body()) {
			if ("9876543210".equals(u.get("username").asString())) {
				ru = u;
			}
		}
		assertThat(ru).as("resident login created with the resident").isNotNull();
		assertThat(ru.get("mustChangePassword").asBoolean()).isTrue();
		assertThat(call("POST", "/api/users", a, "{\"username\":\"asha2\",\"mobile\":\"9876543299\",\"password\":\"Resident#1\","
				+ "\"role\":\"RESIDENT\",\"residentId\":" + residentId + "}").status()).as("one login per resident")
				.isEqualTo(400);
		assertThat(call("POST", "/api/users", a, "{\"username\":\"boss\",\"mobile\":\"9876543288\",\"password\":\"Password#1\","
				+ "\"role\":\"SUPER_ADMIN\"}").status()).as("cannot mint super admin").isEqualTo(400);

		String res = login("9876543210", "9012");
		Res dues = call("GET", "/api/me/dues", res, null);
		assertThat(dues.status()).isEqualTo(200);
		// moved to room 102 (rent 6000): billed 6000 - paid 2000
		assertThat(dues.body().get("resident").get("due").decimalValue().intValue()).isEqualTo(4000);
		assertThat(call("GET", "/api/rooms", res, null).body().get(0).get("occupants").isNull()).isTrue();
		assertThat(call("POST", "/api/rooms", res, "{\"number\":\"9\",\"capacity\":1,\"rent\":1}").status()).isEqualTo(403);
		assertThat(call("GET", "/api/expenses", res, null).status()).isEqualTo(403);
		assertThat(call("GET", "/api/users", res, null).status()).isEqualTo(403);
		assertThat(call("GET", "/api/me/dues", a, null).status()).as("staff has no /me").isEqualTo(403);

		// --- refresh ---
		Res refreshed = call("POST", "/api/auth/refresh", a, "{}");
		assertThat(refreshed.status()).isEqualTo(200);
		assertThat(call("GET", "/api/rooms", refreshed.body().get("token").asString(), null).status()).isEqualTo(200);
		assertThat(call("POST", "/api/auth/refresh", "garbage", "{}").status()).isEqualTo(401);

		// --- blocking a user kills their existing token immediately ---
		long residentUserId = ru.get("id").asLong();
		assertThat(call("PUT", "/api/users/" + residentUserId, a, "{\"status\":\"BLOCKED\"}").status()).isEqualTo(200);
		assertThat(call("GET", "/api/me/dues", res, null).status()).isEqualTo(401);
		assertThat(call("POST", "/api/auth/login", null, "{\"username\":\"9876543210\",\"password\":\"9012\"}").status())
				.isEqualTo(401);

		// --- expired licence blocks the PG's tokens ---
		long pgA = -1;
		for (JsonNode p : call("GET", "/api/admin/pgs", admin, null).body()) {
			if ("Sunrise PG".equals(p.get("name").asString())) {
				pgA = p.get("id").asLong();
			}
		}
		assertThat(call("PUT", "/api/admin/pgs/" + pgA + "/status?active=false", admin, null).status()).isEqualTo(200);
		assertThat(call("GET", "/api/rooms", a, null).status()).isEqualTo(401);
		assertThat(call("POST", "/api/auth/login", null, "{\"username\":\"ownerA\",\"password\":\"OwnerPass#1\"}").status())
				.isEqualTo(402);

		// --- super admin must pick a PG before tenant data ---
		assertThat(call("GET", "/api/rooms", admin, null).status()).isEqualTo(400);
		Res sel = call("POST", "/api/auth/select-pg", admin, "{\"pgId\":" + pgA + "}");
		assertThat(sel.status()).isEqualTo(200);
		assertThat(call("GET", "/api/rooms", sel.body().get("token").asString(), null).status()).isEqualTo(200);

		// --- password change ---
		String b2 = login("ownerB", "OwnerPass#1");
		assertThat(call("PUT", "/api/auth/change-password", b2,
				"{\"oldPassword\":\"nope\",\"newPassword\":\"NewPassword#1\"}").status()).isEqualTo(400);
		assertThat(call("PUT", "/api/auth/change-password", b2,
				"{\"oldPassword\":\"OwnerPass#1\",\"newPassword\":\"NewPassword#1\"}").status()).isEqualTo(200);
		login("ownerB", "NewPassword#1");
	}

	@Test
	void roomRentChangeAppliesFromNextCycleAndToNewResidents() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		createPg(admin, "Rent PG", "ownerRent", "9000000033");
		String o = login("ownerRent", "OwnerPass#1");

		long roomId = call("POST", "/api/rooms", o, "{\"number\":\"301\",\"capacity\":2,\"rent\":5000}").body().get("id")
				.asLong();

		// Joined 40 days ago: two cycles are already billed (10000); the next cycle starts in the future.
		String joined = LocalDate.now().minusDays(40).toString();
		Res old = call("POST", "/api/residents", o,
				"{\"name\":\"Old\",\"phone\":\"9000000411\",\"aadhaar\":\"123456789012\",\"roomId\":" + roomId + ",\"joinedOn\":\"" + joined + "\"}");
		assertThat(old.status()).isEqualTo(200);
		long oldId = old.body().get("id").asLong();
		assertThat(old.body().get("billed").decimalValue().intValue()).isEqualTo(10000);

		assertThat(call("PUT", "/api/rooms/" + roomId + "/rent", o, "{\"rent\":-1}").status()).isEqualTo(400);
		Res changed = call("PUT", "/api/rooms/" + roomId + "/rent", o, "{\"rent\":6000}");
		assertThat(changed.status()).isEqualTo(200);
		assertThat(changed.body().get("rent").decimalValue().intValue()).isEqualTo(6000);

		// Existing resident: cycles already billed keep 5000, the new rent is scheduled for the next cycle.
		Res oldAfter = call("GET", "/api/residents/" + oldId, o, null);
		assertThat(oldAfter.body().get("billed").decimalValue().intValue()).isEqualTo(10000);
		assertThat(oldAfter.body().get("rent").decimalValue().intValue()).isEqualTo(5000);
		assertThat(oldAfter.body().get("rentChanges").size()).isEqualTo(1);
		assertThat(oldAfter.body().get("rentChanges").get(0).get("rent").decimalValue().intValue()).isEqualTo(6000);
		assertThat(oldAfter.body().get("rentChanges").get(0).get("effectiveFrom").asString())
				.isEqualTo(LocalDate.now().minusDays(40).plusMonths(2).toString());

		// Updating the resident without touching the rent keeps the scheduled change.
		assertThat(call("PUT", "/api/residents/" + oldId, o,
				"{\"name\":\"Old\",\"roomId\":" + roomId + "}").status()).isEqualTo(200);
		assertThat(call("GET", "/api/residents/" + oldId, o, null).body().get("rentChanges").size()).isEqualTo(1);

		// A new resident pays the new rent straight away.
		Res fresh = call("POST", "/api/residents", o,
				"{\"name\":\"Fresh\",\"phone\":\"9000000412\",\"aadhaar\":\"123456789012\",\"roomId\":" + roomId + ",\"joinedOn\":\"" + LocalDate.now() + "\"}");
		assertThat(fresh.status()).isEqualTo(200);
		assertThat(fresh.body().get("rent").decimalValue().intValue()).isEqualTo(6000);
		assertThat(fresh.body().get("billed").decimalValue().intValue()).isEqualTo(6000);
		assertThat(fresh.body().get("rentChanges").size()).isZero();
	}

	@Test
	void ownerWithSeveralPropertiesCanSwitchAndSeeConsolidatedTotals() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		createPg(admin, "Multi One", "ownerMulti", "9000000044");
		createPg(admin, "Stranger PG", "ownerStranger", "9000000045");
		String o = login("ownerMulti", "OwnerPass#1");
		String stranger = login("ownerStranger", "OwnerPass#1");

		// The owner adds a second property; it shows up in their list.
		Res added = call("POST", "/api/pg/properties", o, "{\"name\":\"Multi Two\",\"city\":\"Hindupur\"}");
		assertThat(added.status()).isEqualTo(200);
		long secondId = added.body().get("id").asLong();
		Res mine = call("GET", "/api/pg/properties", o, null);
		assertThat(mine.body().size()).isEqualTo(2);
		assertThat(call("GET", "/api/pg/properties", stranger, null).body().size()).isEqualTo(1);

		// Property one: a room, a resident and a payment.
		long room1 = call("POST", "/api/rooms", o, "{\"number\":\"1\",\"capacity\":2,\"rent\":5000}").body().get("id")
				.asLong();
		long res1 = call("POST", "/api/residents", o,
				"{\"name\":\"A\",\"phone\":\"9000000413\",\"aadhaar\":\"123456789012\",\"roomId\":" + room1 + ",\"joinedOn\":\"" + LocalDate.now() + "\"}").body().get("id")
				.asLong();
		assertThat(call("POST", "/api/payments", o, "{\"residentId\":" + res1 + ",\"amount\":1000}").status())
				.isEqualTo(200);

		// Switch to the second property: it has its own, empty, data.
		Res sel = call("POST", "/api/auth/select-pg", o, "{\"pgId\":" + secondId + "}");
		assertThat(sel.status()).isEqualTo(200);
		String o2 = sel.body().get("token").asString();
		assertThat(call("GET", "/api/rooms", o2, null).body().size()).isZero();
		long room2 = call("POST", "/api/rooms", o2, "{\"number\":\"1\",\"capacity\":3,\"rent\":4000}").body().get("id")
				.asLong();
		long res2 = call("POST", "/api/residents", o2,
				"{\"name\":\"B\",\"phone\":\"9000000414\",\"aadhaar\":\"123456789012\",\"roomId\":" + room2 + ",\"joinedOn\":\"" + LocalDate.now() + "\"}").body().get("id")
				.asLong();
		assertThat(call("POST", "/api/payments", o2, "{\"residentId\":" + res2 + ",\"amount\":500}").status())
				.isEqualTo(200);
		assertThat(call("GET", "/api/dashboard/summary", o2, null).body().get("collections").get("total")
				.decimalValue().intValue()).isEqualTo(500);

		// The old token is still scoped to property one.
		assertThat(call("GET", "/api/dashboard/summary", o, null).body().get("collections").get("total")
				.decimalValue().intValue()).isEqualTo(1000);

		// Refresh keeps the property the owner switched to.
		Res refreshed = call("POST", "/api/auth/refresh", o2, "{}");
		assertThat(call("GET", "/api/rooms", refreshed.body().get("token").asString(), null).body().size())
				.isEqualTo(1);

		// Consolidated view across both properties.
		Res all = call("GET", "/api/dashboard/summary?scope=all", o, null);
		assertThat(all.status()).isEqualTo(200);
		assertThat(all.body().get("collections").get("total").decimalValue().intValue()).isEqualTo(1500);
		assertThat(all.body().get("rooms").asInt()).isEqualTo(2);
		assertThat(all.body().get("beds").asInt()).isEqualTo(5);
		assertThat(all.body().get("occupied").asInt()).isEqualTo(2);
		assertThat(all.body().get("properties").size()).isEqualTo(2);
		Res todayAll = call("GET", "/api/dashboard/today?scope=all", o, null);
		assertThat(todayAll.body().get("payments").size()).isEqualTo(2);
		assertThat(todayAll.body().get("collected").decimalValue().intValue()).isEqualTo(1500);

		// An owner cannot open somebody else's property, and cannot be consolidated with it.
		long otherId = -1;
		for (JsonNode p : call("GET", "/api/admin/pgs", admin, null).body()) {
			if ("Stranger PG".equals(p.get("name").asString())) {
				otherId = p.get("id").asLong();
			}
		}
		assertThat(call("POST", "/api/auth/select-pg", o, "{\"pgId\":" + otherId + "}").status()).isEqualTo(403);
		assertThat(call("GET", "/api/dashboard/summary?scope=all", stranger, null).body().get("properties").size())
				.isEqualTo(1);
		// Only owners have a consolidated view.
		assertThat(call("GET", "/api/dashboard/summary?scope=all", admin, null).status()).isEqualTo(403);
	}

	@Test
	void superAdminConsoleStatsUsersAndExitPg() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		long pgId = createPg(admin, "Console PG", "ownerConsole", "9000000061");

		Res stats = call("GET", "/api/admin/stats", admin, null);
		assertThat(stats.status()).isEqualTo(200);
		assertThat(stats.body().get("totalPgs").asInt()).isGreaterThanOrEqualTo(1);
		assertThat(stats.body().get("owners").asInt()).isGreaterThanOrEqualTo(1);

		// Platform-wide users: the owner is listed with its PG, super admins are not.
		long ownerId = -1;
		for (JsonNode u : call("GET", "/api/admin/users", admin, null).body()) {
			assertThat(u.get("role").asString()).isNotEqualTo("SUPER_ADMIN");
			if ("ownerConsole".equals(u.get("username").asString())) {
				ownerId = u.get("id").asLong();
				assertThat(u.get("pgName").asString()).isEqualTo("Console PG");
			}
		}
		assertThat(ownerId).isPositive();

		// Block, then the owner can no longer log in; unblock restores it.
		assertThat(call("PUT", "/api/admin/users/" + ownerId, admin, "{\"status\":\"BLOCKED\"}").status()).isEqualTo(200);
		assertThat(call("POST", "/api/auth/login", null, "{\"username\":\"ownerConsole\",\"password\":\"OwnerPass#1\"}")
				.status()).isEqualTo(401);
		assertThat(call("PUT", "/api/admin/users/" + ownerId, admin, "{\"status\":\"ACTIVE\"}").status()).isEqualTo(200);
		String owner = login("ownerConsole", "OwnerPass#1");

		// An unmapped route must answer 404, not 401 (the app treats a 401 as an expired session and signs out).
		assertThat(call("GET", "/api/admin/nope", admin, null).status()).isEqualTo(404);

		// Users of one PG: the owner is listed, with no password hash.
		Res pgUsers = call("GET", "/api/admin/pgs/" + pgId + "/users", admin, null);
		assertThat(pgUsers.status()).isEqualTo(200);
		assertThat(pgUsers.body().size()).isEqualTo(1);
		assertThat(pgUsers.body().get(0).get("username").asString()).isEqualTo("ownerConsole");
		assertThat(pgUsers.body().get(0).has("password")).isFalse();
		// Owners cannot use the admin console.
		assertThat(call("GET", "/api/admin/stats", owner, null).status()).isEqualTo(403);
		assertThat(call("POST", "/api/auth/exit-pg", owner, "{}").status()).isEqualTo(403);

		// Enter a PG, then leave it again: the new token has no PG.
		String inside = call("POST", "/api/auth/select-pg", admin, "{\"pgId\":" + pgId + "}").body().get("token").asString();
		assertThat(call("GET", "/api/rooms", inside, null).status()).isEqualTo(200);
		Res out = call("POST", "/api/auth/exit-pg", inside, "{}");
		assertThat(out.status()).isEqualTo(200);
		assertThat(out.body().get("pgId").isNull()).isTrue();
		assertThat(call("GET", "/api/admin/stats", out.body().get("token").asString(), null).status()).isEqualTo(200);
	}

	@Test
	void superAdminCreatesUsersAndAssignsSeveralPgs() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		long pgA = createPg(admin, "Assign A", "ownerAssignA", "9000000071");
		long pgB = createPg(admin, "Assign B", "ownerAssignB", "9000000072");
		long pgC = createPg(admin, "Assign C", "ownerAssignC", "9000000073");

		// A new owner login with two PGs can open both and nothing else.
		Res made = call("POST", "/api/admin/users", admin, "{\"username\":\"multiOwner\",\"mobile\":\"9000000074\","
				+ "\"password\":\"OwnerPass#1\",\"role\":\"OWNER\",\"pgIds\":[" + pgA + "," + pgB + "]}");
		assertThat(made.status()).isEqualTo(200);
		long ownerId = made.body().get("id").asLong();
		assertThat(made.body().get("pgIds").size()).isEqualTo(2);
		String owner = login("multiOwner", "OwnerPass#1");
		assertThat(call("GET", "/api/pg/properties", owner, null).body().size()).isEqualTo(2);
		assertThat(call("POST", "/api/auth/select-pg", owner, "{\"pgId\":" + pgB + "}").status()).isEqualTo(200);
		assertThat(call("POST", "/api/auth/select-pg", owner, "{\"pgId\":" + pgC + "}").status()).isEqualTo(403);

		// Reassign: drop A, add C. The owner's list follows.
		Res edited = call("PUT", "/api/admin/users/" + ownerId, admin, "{\"pgIds\":[" + pgB + "," + pgC + "]}");
		assertThat(edited.status()).as(String.valueOf(edited.body())).isEqualTo(200);
		assertThat(edited.body().get("pgIds").size()).isEqualTo(2);
		String owner2 = login("multiOwner", "OwnerPass#1");
		assertThat(call("GET", "/api/pg/properties", owner2, null).body().size()).isEqualTo(2);
		assertThat(call("POST", "/api/auth/select-pg", owner2, "{\"pgId\":" + pgA + "}").status()).isEqualTo(403);
		assertThat(call("POST", "/api/auth/select-pg", owner2, "{\"pgId\":" + pgC + "}").status()).isEqualTo(200);

		// A manager gets exactly one PG.
		assertThat(call("POST", "/api/admin/users", admin, "{\"username\":\"badMgr\",\"mobile\":\"9000000075\","
				+ "\"password\":\"MgrPass#123\",\"role\":\"MANAGER\",\"pgIds\":[" + pgA + "," + pgB + "]}").status())
				.isEqualTo(400);
		assertThat(call("POST", "/api/admin/users", admin, "{\"username\":\"newMgr\",\"mobile\":\"9000000076\","
				+ "\"password\":\"MgrPass#123\",\"role\":\"MANAGER\",\"pgIds\":[" + pgA + "]}").status()).isEqualTo(200);
		String mgr = login("newMgr", "MgrPass#123");
		assertThat(call("GET", "/api/rooms", mgr, null).status()).isEqualTo(200);

		// Unknown PG, duplicate username, and a role that cannot be created here are all rejected.
		assertThat(call("POST", "/api/admin/users", admin, "{\"username\":\"ghost\",\"mobile\":\"9000000077\","
				+ "\"password\":\"OwnerPass#1\",\"role\":\"OWNER\",\"pgIds\":[999999]}").status()).isEqualTo(400);
		assertThat(call("POST", "/api/admin/users", admin, "{\"username\":\"newMgr\",\"mobile\":\"9000000078\","
				+ "\"password\":\"MgrPass#123\",\"role\":\"MANAGER\",\"pgIds\":[" + pgA + "]}").status()).isEqualTo(400);
		assertThat(call("POST", "/api/admin/users", admin, "{\"username\":\"resLogin\",\"mobile\":\"9000000079\","
				+ "\"password\":\"MgrPass#123\",\"role\":\"RESIDENT\",\"pgIds\":[" + pgA + "]}").status()).isEqualTo(400);
	}
	@Test
	void lookupTableDrivesDropdownsAndDefaultUnitLabel() throws Exception {
		assertThat(call("GET", "/api/lookups?category=STAY_TYPE", null, null).status()).as("public: the owner sign-up form uses it").isEqualTo(200);
		String admin = login("superadmin", "DevAdmin#123");
		Res types = call("GET", "/api/lookups?category=STAY_TYPE", admin, null);
		assertThat(types.status()).isEqualTo(200);
		// The five built-in types plus Hotel, Resort, Villa and Independent House added by ConfigSeeder (other tests may add more).
		assertThat(types.body().size()).isGreaterThanOrEqualTo(9);
		assertThat(types.body().get(0).get("code").asString()).isEqualTo("APARTMENT");
		assertThat(types.body().get(0).get("extra").asString()).isEqualTo("Flat");
		assertThat(call("GET", "/api/lookups?category=GENDER_POLICY", admin, null).body().size()).isEqualTo(4);
		assertThat(call("GET", "/api/lookups?category=NOPE", admin, null).body().size()).isEqualTo(0);

		// With no unit label supplied, the stay type's table value is used.
		Res flat = call("POST", "/api/admin/pgs", admin, "{\"name\":\"Lookup Flats\",\"stayType\":\"APARTMENT\","
				+ "\"ownerUsername\":\"ownerLookupA\",\"ownerMobile\":\"9000000081\",\"ownerPassword\":\"OwnerPass#1\"}");
		assertThat(flat.status()).isEqualTo(200);
		assertThat(flat.body().get("unitLabel").asString()).isEqualTo("Flat");
		Res dorm = call("POST", "/api/admin/pgs", admin, "{\"name\":\"Lookup Dorms\",\"stayType\":\"hostel\","
				+ "\"ownerUsername\":\"ownerLookupB\",\"ownerMobile\":\"9000000082\",\"ownerPassword\":\"OwnerPass#1\"}");
		assertThat(dorm.body().get("unitLabel").asString()).isEqualTo("Dorm");
	}

	@Test
	void roomDetailsCanBeEditedWithoutTouchingBedsOrRent() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		createPg(admin, "Edit Room PG", "ownerEditRoom", "9000000091");
		String o = login("ownerEditRoom", "OwnerPass#1");
		Res created = call("POST", "/api/rooms", o, "{\"number\":\"E1\",\"capacity\":2,\"rent\":5000,\"floorName\":\"Ground Floor\"}");
		assertThat(created.status()).isEqualTo(200);
		long id = created.body().get("id").asLong();
		call("POST", "/api/rooms", o, "{\"number\":\"E2\",\"capacity\":1,\"rent\":4000}");

		// Same body the app sends: details only, no capacity or rent.
		Res edited = call("PUT", "/api/rooms/" + id, o, "{\"number\":\"E1A\",\"roomName\":\"Corner\",\"buildingName\":\"Block B\","
				+ "\"floorName\":\"1st Floor\",\"roomType\":\"STANDARD\",\"acType\":\"AC\",\"genderPolicy\":\"FEMALE\","
				+ "\"attachedBathroom\":true,\"balcony\":false}");
		assertThat(edited.status()).as(String.valueOf(edited.body())).isEqualTo(200);
		assertThat(edited.body().get("number").asString()).isEqualTo("E1A");
		assertThat(edited.body().get("floorName").asString()).isEqualTo("1st Floor");
		assertThat(edited.body().get("acType").asString()).isEqualTo("AC");
		assertThat(edited.body().get("genderPolicy").asString()).isEqualTo("FEMALE");
		assertThat(edited.body().get("capacity").asInt()).isEqualTo(2);
		assertThat(edited.body().get("rent").asDouble()).isEqualTo(5000.0);

		// Renaming onto another room's number is rejected.
		assertThat(call("PUT", "/api/rooms/" + id, o, "{\"number\":\"E2\"}").status()).isEqualTo(400);
	}

	@Test
	void anOwnerRegistersAndCanOnlySignInOnceTheSuperAdminApproves() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		String form = "{\"ownerName\":\"Meena\",\"mobile\":\"9000000601\",\"password\":\"Meena#123\",\"name\":\"Meena Stay\","
				+ "\"stayType\":\"PG\",\"city\":\"Pune\",\"upiId\":\"meena@upi\",\"totalFloors\":2,\"startingPrice\":6000}";

		// The sign-up form reads its dropdowns before anyone is signed in.
		assertThat(call("GET", "/api/lookups?category=STAY_TYPE", null, null).status()).isEqualTo(200);
		assertThat(call("POST", "/api/public/owner-registrations", null, "{\"ownerName\":\"X\"}").status()).isEqualTo(400);

		assertThat(call("POST", "/api/public/owner-registrations", null, form).status()).isEqualTo(200);
		assertThat(call("POST", "/api/public/owner-registrations", null, form).status())
				.as("one open request per mobile").isEqualTo(400);

		// Nothing exists yet: no property in the public list and no usable login.
		for (JsonNode p : call("GET", "/api/public/pgs", null, null).body()) {
			assertThat(p.get("name").asString()).isNotEqualTo("Meena Stay");
		}
		Res waiting = call("POST", "/api/auth/login", null, "{\"username\":\"9000000601\",\"password\":\"Meena#123\"}");
		assertThat(waiting.status()).isEqualTo(403);
		assertThat(waiting.body().get("message").asString()).contains("waiting");
		assertThat(call("POST", "/api/auth/login", null, "{\"username\":\"9000000601\",\"password\":\"wrong\"}").status())
				.as("a wrong password learns nothing").isEqualTo(401);

		// Only the super admin sees and decides requests.
		assertThat(call("GET", "/api/admin/registrations", null, null).status()).isEqualTo(401);
		createPg(admin, "Reg Owner PG", "ownerReg", "9000000602");
		String owner = login("ownerReg", "OwnerPass#1");
		assertThat(call("GET", "/api/admin/registrations", owner, null).status()).isEqualTo(403);

		long id = -1;
		for (JsonNode r : call("GET", "/api/admin/registrations?status=PENDING", admin, null).body()) {
			if ("9000000601".equals(r.get("mobile").asString())) id = r.get("id").asLong();
		}
		assertThat(id).isPositive();
		assertThat(call("GET", "/api/admin/stats", admin, null).body().get("pendingRegistrations").asInt()).isPositive();

		Res approved = call("POST", "/api/admin/registrations/" + id + "/approve", admin, "{}");
		assertThat(approved.status()).isEqualTo(200);
		assertThat(approved.body().get("status").asString()).isEqualTo("APPROVED");
		assertThat(call("POST", "/api/admin/registrations/" + id + "/approve", admin, "{}").status())
				.as("decided once").isEqualTo(400);

		// Now the owner signs in with the password chosen at sign-up, with nothing to change, and the stay is public.
		Res in = call("POST", "/api/auth/login", null, "{\"username\":\"9000000601\",\"password\":\"Meena#123\"}");
		assertThat(in.status()).isEqualTo(200);
		assertThat(in.body().get("role").asString()).isEqualTo("OWNER");
		assertThat(in.body().get("mustChangePassword").asBoolean()).isFalse();
		boolean listed = false;
		for (JsonNode p : call("GET", "/api/public/pgs", null, null).body()) {
			listed |= "Meena Stay".equals(p.get("name").asString());
		}
		assertThat(listed).isTrue();

		// A refused request says so, with the reason.
		String refused = "{\"ownerName\":\"Ravi\",\"mobile\":\"9000000603\",\"password\":\"Ravi#1234\",\"name\":\"Ravi Stay\"}";
		assertThat(call("POST", "/api/public/owner-registrations", null, refused).status()).isEqualTo(200);
		long rid = -1;
		for (JsonNode r : call("GET", "/api/admin/registrations?status=PENDING", admin, null).body()) {
			if ("9000000603".equals(r.get("mobile").asString())) rid = r.get("id").asLong();
		}
		assertThat(call("POST", "/api/admin/registrations/" + rid + "/reject", admin, "{\"reason\":\"Incomplete address\"}")
				.status()).isEqualTo(200);
		Res no = call("POST", "/api/auth/login", null, "{\"username\":\"9000000603\",\"password\":\"Ravi#1234\"}");
		assertThat(no.status()).isEqualTo(403);
		assertThat(no.body().get("message").asString()).contains("Incomplete address");

		assertThat(call("POST", "/api/auth/register", null, "{\"name\":\"Old\",\"mobile\":\"9000000604\",\"password\":\"abcd\"}")
				.status()).as("residents no longer self-register").isIn(401, 403, 404, 405);
	}

	@Test
	void addingAResidentCreatesTheirLoginWithMobileAndAadhaarLast4AndForcesAPasswordChange() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		createPg(admin, "Auto Login PG", "ownerAutoLogin", "9000000095");
		String o = login("ownerAutoLogin", "OwnerPass#1");
		long roomId = call("POST", "/api/rooms", o, "{\"number\":\"L1\",\"capacity\":3,\"rent\":5000}").body().get("id").asLong();
		String base = ",\"roomId\":" + roomId + ",\"joinedOn\":\"" + LocalDate.now() + "\"}";

		assertThat(call("POST", "/api/residents", o, "{\"name\":\"NoPhone\",\"aadhaar\":\"234567890123\"" + base).status())
				.as("mobile number is mandatory").isEqualTo(400);
		assertThat(call("POST", "/api/residents", o, "{\"name\":\"NoAadhaar\",\"phone\":\"9000000511\"" + base).status())
				.as("aadhaar is mandatory").isEqualTo(400);
		assertThat(call("POST", "/api/residents", o,
				"{\"name\":\"Short\",\"phone\":\"9000000511\",\"aadhaar\":\"1234\"" + base).status())
				.as("aadhaar must be 12 digits").isEqualTo(400);

		Res created = call("POST", "/api/residents", o,
				"{\"name\":\"Login Tenant\",\"phone\":\"9000000511\",\"aadhaar\":\"234567890123\"" + base);
		assertThat(created.status()).isEqualTo(200);
		assertThat(created.body().toString()).as("aadhaar is never returned").doesNotContain("234567890123");

		assertThat(call("POST", "/api/auth/login", null, "{\"username\":\"9000000511\",\"password\":\"0123\"}")
				.body().get("mustChangePassword").asBoolean()).as("first login forces a password change").isTrue();
		assertThat(call("POST", "/api/auth/login", null, "{\"username\":\"9000000511\",\"password\":\"234567890123\"}")
				.status()).as("only the last 4 digits are the password").isEqualTo(401);

		String res = login("9000000511", "0123");
		assertThat(call("PUT", "/api/auth/change-password", res, "{\"oldPassword\":\"0123\",\"newPassword\":\"Brand#New1\"}")
				.status()).isEqualTo(200);
		assertThat(call("POST", "/api/auth/login", null, "{\"username\":\"9000000511\",\"password\":\"Brand#New1\"}")
				.body().get("mustChangePassword").asBoolean()).isFalse();
	}

	@Test
	void residentNoticeToVacateNeedsOwnerApprovalBeforeBedIsAdvertised() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		long pgId = createPg(admin, "Notice PG", "ownerNotice", "9000000101");
		String o = login("ownerNotice", "OwnerPass#1");
		long roomId = call("POST", "/api/rooms", o, "{\"number\":\"N1\",\"capacity\":1,\"rent\":5000}").body().get("id").asLong();
		long residentId = call("POST", "/api/residents", o, "{\"name\":\"Leaver\",\"phone\":\"9000000102\",\"aadhaar\":\"123456789012\",\"roomId\":" + roomId
				+ ",\"joinedOn\":\"" + LocalDate.now() + "\"}").body().get("id").asLong();
		assertThat(call("POST", "/api/payments", o, "{\"residentId\":" + residentId + ",\"amount\":5000}").status()).isEqualTo(200);
		String res = login("9000000102", "9012");
		String leaveOn = LocalDate.now().plusDays(30).toString();

		// Too short a notice, and staff cannot use the resident endpoint.
		assertThat(call("POST", "/api/me/vacate-notice", res, "{\"vacateOn\":\"" + LocalDate.now().plusDays(2) + "\"}").status())
				.as("less than 30 days").isEqualTo(400);
		assertThat(call("POST", "/api/me/vacate-notice", o, "{\"vacateOn\":\"" + leaveOn + "\"}").status()).isEqualTo(403);

		// Resident raises the notice: it is only a request, so nothing is advertised yet.
		Res given = call("POST", "/api/me/vacate-notice", res, "{\"vacateOn\":\"" + leaveOn + "\",\"reason\":\"Job change\"}");
		assertThat(given.status()).isEqualTo(200);
		assertThat(given.body().get("noticeStatus").asString()).isEqualTo("REQUESTED");
		assertThat(given.body().get("vacateOn").asString()).isEqualTo(leaveOn);
		assertThat(call("POST", "/api/me/vacate-notice", res, "{\"vacateOn\":\"" + leaveOn + "\"}").status())
				.as("only one notice at a time").isEqualTo(400);
		assertThat(call("GET", "/api/public/pgs/" + pgId, null, null).body().get("rooms").get(0).get("allVacantBeds").size()).isZero();

		// Owner approves: the bed is now offered "available from" the leaving date, but is not counted as vacant today.
		assertThat(call("POST", "/api/residents/" + residentId + "/notice/approve", res, null).status()).isEqualTo(403);
		Res approved = call("POST", "/api/residents/" + residentId + "/notice/approve", o, null);
		assertThat(approved.status()).isEqualTo(200);
		assertThat(approved.body().get("noticeStatus").asString()).isEqualTo("APPROVED");
		JsonNode room = call("GET", "/api/public/pgs/" + pgId, null, null).body().get("rooms").get(0);
		assertThat(room.get("vacantBedsCount").asInt()).isZero();
		assertThat(room.get("allVacantBeds").size()).isEqualTo(1);
		assertThat(room.get("allVacantBeds").get(0).get("availableFrom").asString()).isEqualTo(leaveOn);
		assertThat(call("GET", "/api/public/pgs/" + pgId, null, null).body().get("totalVacantBeds").asInt()).isZero();
		assertThat(call("GET", "/api/rooms", o, null).body().get(0).get("sections").get(0).get("beds").get(0)
				.get("availableFrom").asString()).isEqualTo(leaveOn);

		// Withdrawing the notice takes the bed off the market again; a declined notice never advertises it.
		Res cancelled = call("DELETE", "/api/me/vacate-notice", res, null);
		assertThat(cancelled.status()).isEqualTo(200);
		assertThat(cancelled.body().get("noticeStatus").isNull()).isTrue();
		assertThat(call("GET", "/api/public/pgs/" + pgId, null, null).body().get("rooms").get(0).get("allVacantBeds").size()).isZero();
		assertThat(call("POST", "/api/me/vacate-notice", res, "{\"vacateOn\":\"" + leaveOn + "\"}").status()).isEqualTo(200);
		assertThat(call("POST", "/api/residents/" + residentId + "/notice/reject", o, null).body().get("noticeStatus").asString())
				.isEqualTo("REJECTED");
		assertThat(call("GET", "/api/public/pgs/" + pgId, null, null).body().get("rooms").get(0).get("allVacantBeds").size()).isZero();
	}

	@Test
	void residentComplaintReachesStaffWhoResolveItAndTheResidentSeesTheOutcome() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		createPg(admin, "Complaint PG", "ownerComplaint", "9000000201");
		String o = login("ownerComplaint", "OwnerPass#1");
		long roomId = call("POST", "/api/rooms", o, "{\"number\":\"C1\",\"capacity\":2,\"rent\":5000}").body().get("id").asLong();
		long residentId = call("POST", "/api/residents", o, "{\"name\":\"Complainer\",\"phone\":\"9000000202\",\"aadhaar\":\"123456789012\",\"roomId\":" + roomId
				+ ",\"joinedOn\":\"" + LocalDate.now() + "\"}").body().get("id").asLong();
		long otherId = call("POST", "/api/residents", o, "{\"name\":\"Bystander\",\"phone\":\"9000000203\",\"aadhaar\":\"123456789012\",\"roomId\":" + roomId
				+ ",\"joinedOn\":\"" + LocalDate.now() + "\"}").body().get("id").asLong();
		assertThat(call("POST", "/api/users", o, "{\"username\":\"mgrComplaint\",\"mobile\":\"9000000204\",\"password\":\"MgrPass#123\","
				+ "\"role\":\"MANAGER\"}").status()).isEqualTo(200);
		String res = login("9000000202", "9012");
		String other = login("9000000203", "9012");
		String mgr = login("mgrComplaint", "MgrPass#123");

		// Categories come from the lookup table, not from the app.
		Res cats = call("GET", "/api/lookups?category=COMPLAINT_CATEGORY", res, null);
		assertThat(cats.status()).isEqualTo(200);
		assertThat(cats.body().size()).isGreaterThan(0);
		String category = cats.body().get(0).get("code").asString();

		// Only a resident raises complaints, and the category must be one the table offers.
		String body = "{\"category\":\"" + category + "\",\"title\":\"Tap leaking\",\"description\":\"Bathroom tap drips\",\"priority\":\"HIGH\"}";
		assertThat(call("POST", "/api/me/complaints", o, body).status()).isEqualTo(403);
		assertThat(call("POST", "/api/me/complaints", res,
				"{\"category\":\"NOPE\",\"title\":\"x\",\"description\":\"y\"}").status()).isEqualTo(400);
		assertThat(call("POST", "/api/me/complaints", res, "{\"category\":\"" + category + "\",\"title\":\"\",\"description\":\"y\"}")
				.status()).isEqualTo(400);

		Res raised = call("POST", "/api/me/complaints", res, body);
		assertThat(raised.status()).isEqualTo(200);
		long id = raised.body().get("id").asLong();
		assertThat(raised.body().get("status").asString()).isEqualTo("OPEN");
		assertThat(raised.body().get("priority").asString()).isEqualTo("HIGH");

		// The owner and the manager both see it, with who raised it and where they stay; other residents do not.
		assertThat(call("GET", "/api/complaints", res, null).status()).isEqualTo(403);
		for (String staff : new String[] { o, mgr }) {
			Res list = call("GET", "/api/complaints", staff, null);
			assertThat(list.status()).isEqualTo(200);
			assertThat(list.body().size()).isEqualTo(1);
			assertThat(list.body().get(0).get("residentName").asString()).isEqualTo("Complainer");
			assertThat(list.body().get(0).get("roomNumber").asString()).isEqualTo("C1");
			assertThat(list.body().get(0).get("categoryLabel").asString()).isNotBlank();
		}
		assertThat(call("GET", "/api/complaints/count", o, null).body().get("pending").asInt()).isEqualTo(1);
		assertThat(call("GET", "/api/me/complaints", other, null).body().size()).isZero();
		assertThat(call("GET", "/api/me/complaints", res, null).body().size()).isEqualTo(1);

		// A resident cannot resolve their own complaint.
		assertThat(call("PUT", "/api/complaints/" + id, res, "{\"status\":\"RESOLVED\"}").status()).isEqualTo(403);
		assertThat(call("PUT", "/api/complaints/9999", o, "{\"status\":\"RESOLVED\"}").status()).isEqualTo(404);
		assertThat(call("PUT", "/api/complaints/" + id, o, "{\"status\":\"BOGUS\"}").status()).isEqualTo(400);

		// The manager picks it up: the resident now has an unseen update, and it is no longer counted as waiting.
		Res started = call("PUT", "/api/complaints/" + id, mgr, "{\"status\":\"IN_PROGRESS\",\"resolutionNotes\":\"Plumber booked\"}");
		assertThat(started.status()).isEqualTo(200);
		assertThat(started.body().get("status").asString()).isEqualTo("IN_PROGRESS");
		assertThat(started.body().get("handledBy").asString()).isEqualTo("mgrComplaint");
		assertThat(call("GET", "/api/complaints/count", o, null).body().get("pending").asInt()).isZero();
		Res mine = call("GET", "/api/me/complaints", res, null);
		assertThat(mine.body().get(0).get("residentSeen").asBoolean()).isFalse();
		assertThat(mine.body().get(0).get("resolutionNotes").asString()).isEqualTo("Plumber booked");

		// The owner resolves it; the resident sees the outcome, then opening the list clears the indicator.
		Res resolved = call("PUT", "/api/complaints/" + id, o, "{\"status\":\"RESOLVED\",\"resolutionNotes\":\"Washer replaced\"}");
		assertThat(resolved.status()).isEqualTo(200);
		assertThat(resolved.body().get("status").asString()).isEqualTo("RESOLVED");
		assertThat(resolved.body().get("resolvedAt").isNull()).isFalse();
		Res after = call("GET", "/api/me/complaints", res, null);
		assertThat(after.body().get(0).get("status").asString()).isEqualTo("RESOLVED");
		assertThat(after.body().get(0).get("resolutionNotes").asString()).isEqualTo("Washer replaced");
		assertThat(after.body().get(0).get("residentSeen").asBoolean()).isFalse();
		assertThat(call("POST", "/api/me/complaints/seen", res, null).status()).isEqualTo(200);
		assertThat(call("GET", "/api/me/complaints", res, null).body().get(0).get("residentSeen").asBoolean()).isTrue();
	}
	@Test
	void residentsRegisterDevicesForPushAndTheEventsStillWorkWithPushSwitchedOff() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		createPg(admin, "Push PG", "ownerPush", "9000000341");
		String o = login("ownerPush", "OwnerPass#1");
		long roomId = call("POST", "/api/rooms", o, "{\"number\":\"P1\",\"capacity\":2,\"rent\":5000}").body().get("id").asLong();
		long resId = call("POST", "/api/residents", o, "{\"name\":\"Pusher\",\"phone\":\"9000000342\",\"aadhaar\":\"123456789012\",\"roomId\":" + roomId
				+ ",\"joinedOn\":\"" + LocalDate.now() + "\"}").body().get("id").asLong();
		String res = login("9000000342", "9012");

		// Any signed-in user (resident or staff) may register a phone; anonymous users may not.
		String device = "{\"token\":\"fcm-token-abc\",\"platform\":\"android\"}";
		assertThat(call("POST", "/api/me/devices", res, device).status()).isEqualTo(200);
		assertThat(call("POST", "/api/me/devices", res, device).status()).as("same token twice is fine").isEqualTo(200);
		assertThat(call("POST", "/api/me/devices", o, device).status()).as("staff (owner)").isEqualTo(200);
		assertThat(call("POST", "/api/me/devices", null, device).status()).as("anonymous").isEqualTo(401);
		assertThat(call("POST", "/api/me/devices", res, "{\"token\":\"\"}").status()).as("blank token").isEqualTo(400);
		assertThat(call("DELETE", "/api/me/devices?token=fcm-token-abc", res, null).status()).isEqualTo(200);

		// The events that send a push keep working when Firebase is not configured.
		assertThat(call("POST", "/api/announcements", o, "{\"title\":\"Water\",\"content\":\"Off 2-4 PM\",\"important\":true}").status()).isEqualTo(200);
		assertThat(call("POST", "/api/payments", o, "{\"residentId\":" + resId + ",\"amount\":500}").status()).isEqualTo(200);
		assertThat(call("POST", "/api/notifications/remind-all-dues", o, null).status()).isEqualTo(200);
		String category = call("GET", "/api/lookups?category=COMPLAINT_CATEGORY", res, null).body().get(0).get("code").asString();
		long cId = call("POST", "/api/me/complaints", res, "{\"category\":\"" + category + "\",\"title\":\"Leak\",\"description\":\"Tap leaks\"}").body().get("id").asLong();
		assertThat(call("PUT", "/api/complaints/" + cId, o, "{\"status\":\"RESOLVED\",\"resolutionNotes\":\"Fixed\"}").status()).isEqualTo(200);
	}

	@Test
	void ownerPostsRulesAndOnlyResidentsOfThatPropertyReadThem() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		createPg(admin, "Rules PG", "ownerRules", "9000000321");
		createPg(admin, "Other Rules PG", "ownerRules2", "9000000331");
		String o = login("ownerRules", "OwnerPass#1");
		String o2 = login("ownerRules2", "OwnerPass#1");
		long roomId = call("POST", "/api/rooms", o, "{\"number\":\"R1\",\"capacity\":2,\"rent\":5000}").body().get("id").asLong();
		call("POST", "/api/residents", o, "{\"name\":\"Reader\",\"phone\":\"9000000322\",\"aadhaar\":\"123456789012\",\"roomId\":" + roomId
				+ ",\"joinedOn\":\"" + LocalDate.now() + "\"}");
		String res = login("9000000322", "9012");

		assertThat(call("GET", "/api/me/rules", res, null).body().get("rules").isNull()).isTrue();
		assertThat(call("GET", "/api/me/rules", o, null).status()).as("staff has no /me").isEqualTo(403);

		JsonNode profile = call("GET", "/api/pg/profile", o, null).body();
		String update = "{\"name\":\"" + profile.get("name").asString() + "\",\"rules\":\"Gate closes at 10:30 PM\"}";
		assertThat(call("PUT", "/api/pg/profile", res, update).status()).isEqualTo(403);
		assertThat(call("PUT", "/api/pg/profile", o, update).status()).isEqualTo(200);

		assertThat(call("GET", "/api/me/rules", res, null).body().get("rules").asString()).isEqualTo("Gate closes at 10:30 PM");
		assertThat(call("GET", "/api/pg/profile", o2, null).body().get("rules").isNull()).isTrue();
	}

	@Test
	void ownerManagesSupportContactsAndResidentsOnlyReadThem() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		createPg(admin, "Support PG", "ownerSupport", "9000000301");
		createPg(admin, "Other Support PG", "ownerSupport2", "9000000311");
		String o = login("ownerSupport", "OwnerPass#1");
		String o2 = login("ownerSupport2", "OwnerPass#1");
		long roomId = call("POST", "/api/rooms", o, "{\"number\":\"S1\",\"capacity\":2,\"rent\":5000}").body().get("id").asLong();
		long residentId = call("POST", "/api/residents", o, "{\"name\":\"Helped\",\"phone\":\"9000000302\",\"aadhaar\":\"123456789012\",\"roomId\":" + roomId
				+ ",\"joinedOn\":\"" + LocalDate.now() + "\"}").body().get("id").asLong();
		String res = login("9000000302", "9012");

		assertThat(call("GET", "/api/support-contacts", res, null).body().size()).isZero();

		assertThat(call("POST", "/api/support-contacts", o, "{\"name\":\"\",\"phone\":\"9000000303\"}").status()).isEqualTo(400);
		assertThat(call("POST", "/api/support-contacts", o, "{\"name\":\"Warden\",\"phone\":\"abc\"}").status()).isEqualTo(400);
		assertThat(call("POST", "/api/support-contacts", res, "{\"name\":\"Warden\",\"phone\":\"9000000303\"}").status()).isEqualTo(403);

		Res added = call("POST", "/api/support-contacts", o, "{\"name\":\"Warden\",\"phone\":\"9000000303\",\"note\":\"24/7\"}");
		assertThat(added.status()).isEqualTo(200);
		long id = added.body().get("id").asLong();

		Res seen = call("GET", "/api/support-contacts", res, null);
		assertThat(seen.body().size()).isEqualTo(1);
		assertThat(seen.body().get(0).get("name").asString()).isEqualTo("Warden");
		assertThat(seen.body().get(0).get("note").asString()).isEqualTo("24/7");
		assertThat(call("GET", "/api/support-contacts", o2, null).body().size()).isZero();

		assertThat(call("PUT", "/api/support-contacts/" + id, res, "{\"name\":\"X\",\"phone\":\"9000000304\"}").status()).isEqualTo(403);
		assertThat(call("PUT", "/api/support-contacts/" + id, o2, "{\"name\":\"X\",\"phone\":\"9000000304\"}").status()).isEqualTo(404);
		Res edited = call("PUT", "/api/support-contacts/" + id, o, "{\"name\":\"Caretaker\",\"phone\":\"9000000304\"}");
		assertThat(edited.body().get("name").asString()).isEqualTo("Caretaker");
		assertThat(edited.body().get("note").isNull()).isTrue();

		assertThat(call("DELETE", "/api/support-contacts/" + id, res, null).status()).isEqualTo(403);
		assertThat(call("DELETE", "/api/support-contacts/" + id, o, null).status()).isEqualTo(204);
		assertThat(call("GET", "/api/support-contacts", res, null).body().size()).isZero();
	}

	@Test
	void superAdminMaintainsLookupValuesAndEveryoneReadsTheActiveOnes() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		createPg(admin, "Lookup PG", "ownerLookup", "9000000401");
		String o = login("ownerLookup", "OwnerPass#1");

		// Only the super admin maintains the table.
		assertThat(call("GET", "/api/admin/lookups", o, null).status()).isEqualTo(403);
		assertThat(call("POST", "/api/admin/lookups", o, "{\"category\":\"X\",\"code\":\"A\",\"label\":\"A\"}").status()).isEqualTo(403);

		// A brand new category needs no code change; names are normalised to upper case.
		Res added = call("POST", "/api/admin/lookups", admin,
				"{\"category\":\"vehicle_type\",\"code\":\"bike\",\"label\":\"Bike\",\"sortOrder\":2}");
		assertThat(added.status()).isEqualTo(200);
		assertThat(added.body().get("category").asString()).isEqualTo("VEHICLE_TYPE");
		assertThat(added.body().get("code").asString()).isEqualTo("BIKE");
		long bikeId = added.body().get("id").asLong();
		assertThat(call("POST", "/api/admin/lookups", admin,
				"{\"category\":\"VEHICLE_TYPE\",\"code\":\"CAR\",\"label\":\"Car\",\"extra\":\"4 wheels\",\"sortOrder\":1}").status())
				.isEqualTo(200);

		// Duplicates and bad keys are rejected.
		assertThat(call("POST", "/api/admin/lookups", admin, "{\"category\":\"VEHICLE_TYPE\",\"code\":\"bike\",\"label\":\"Again\"}").status())
				.isEqualTo(400);
		assertThat(call("POST", "/api/admin/lookups", admin, "{\"category\":\"has space\",\"code\":\"A\",\"label\":\"A\"}").status())
				.isEqualTo(400);
		assertThat(call("POST", "/api/admin/lookups", admin, "{\"category\":\"VEHICLE_TYPE\",\"code\":\"X\",\"label\":\"\"}").status())
				.isEqualTo(400);

		// The owner's dropdown reads it, in sort order.
		Res seen = call("GET", "/api/lookups?category=VEHICLE_TYPE", o, null);
		assertThat(seen.body().size()).isEqualTo(2);
		assertThat(seen.body().get(0).get("code").asString()).isEqualTo("CAR");
		assertThat(seen.body().get(0).get("extra").asString()).isEqualTo("4 wheels");

		// Retiring an option hides it from dropdowns but keeps it in the admin list; it can be switched back on.
		assertThat(call("PUT", "/api/admin/lookups/" + bikeId, admin, "{\"active\":false,\"label\":\"Motorbike\"}").body().get("label").asString())
				.isEqualTo("Motorbike");
		assertThat(call("GET", "/api/lookups?category=VEHICLE_TYPE", o, null).body().size()).isEqualTo(1);
		boolean listed = false;
		for (var row : call("GET", "/api/admin/lookups", admin, null).body()) {
			if (row.get("code").asString().equals("BIKE") && !row.get("active").asBoolean()) {
				listed = true;
			}
		}
		assertThat(listed).isTrue();
		assertThat(call("PUT", "/api/admin/lookups/" + bikeId, admin, "{\"active\":true}").status()).isEqualTo(200);
		assertThat(call("GET", "/api/lookups?category=VEHICLE_TYPE", o, null).body().size()).isEqualTo(2);
		assertThat(call("PUT", "/api/admin/lookups/999999", admin, "{\"active\":true}").status()).isEqualTo(404);
	}

	@Test
	void dynamicPropertyConfigurationFollowsTheCatalog() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		long pgId = createPg(admin, "Cfg PG", "ownerCfg", "9000000501");
		createPg(admin, "Cfg Stranger", "ownerCfgX", "9000000502");
		Res apt = call("POST", "/api/admin/pgs", admin, "{\"name\":\"Cfg Flats\",\"stayType\":\"APARTMENT\","
				+ "\"ownerUsername\":\"ownerCfgA\",\"ownerMobile\":\"9000000503\",\"ownerPassword\":\"OwnerPass#1\"}");
		assertThat(apt.status()).isEqualTo(200);
		long aptId = apt.body().get("id").asLong();
		String o = login("ownerCfg", "OwnerPass#1");
		String stranger = login("ownerCfgX", "OwnerPass#1");
		String oa = login("ownerCfgA", "OwnerPass#1");

		// ── read APIs: any signed-in user, driven by the tables ──
		assertThat(call("GET", "/api/config/property-types", null, null).status()).as("public: the owner sign-up form uses it").isEqualTo(200);
		Res types = call("GET", "/api/config/property-types", o, null);
		assertThat(types.status()).isEqualTo(200);
		assertThat(types.body().size()).isGreaterThanOrEqualTo(9);
		Res pgType = call("GET", "/api/config/property-types/pg", o, null);
		assertThat(pgType.status()).isEqualTo(200);
		assertThat(pgType.body().get("code").asString()).isEqualTo("PG");
		assertThat(pgType.body().get("amenities").size()).isEqualTo(9);
		assertThat(pgType.body().get("fields").size()).isEqualTo(3);
		assertThat(call("GET", "/api/config/property-types/NOPE", o, null).status()).isEqualTo(404);
		assertThat(call("GET", "/api/config/property-types/HOTEL/amenities", o, null).body().size()).isEqualTo(8);
		assertThat(call("GET", "/api/config/property-types/APARTMENT/fields", o, null).body().size()).isEqualTo(2);
		assertThat(call("GET", "/api/config/property-types/VILLA/amenities", o, null).body().size()).isZero();
		assertThat(call("GET", "/api/config/amenity-categories", o, null).body().size()).isEqualTo(10);
		assertThat(call("GET", "/api/config/amenities", o, null).body().size()).isGreaterThan(20);

		// ── admin APIs: super admin only, validated ──
		String sauna = "{\"code\":\"sauna\",\"name\":\"Sauna\",\"categoryCode\":\"RECREATION\",\"applicableLevel\":\"PROPERTY\"}";
		assertThat(call("POST", "/api/admin/amenities", o, sauna).status()).isEqualTo(403);
		Res made = call("POST", "/api/admin/amenities", admin, sauna);
		assertThat(made.status()).isEqualTo(200);
		assertThat(made.body().get("code").asString()).isEqualTo("SAUNA");
		assertThat(call("POST", "/api/admin/amenities", admin, sauna).status()).isEqualTo(400);
		assertThat(call("POST", "/api/admin/amenities", admin,
				"{\"code\":\"X1\",\"name\":\"X\",\"categoryCode\":\"NOPE\"}").status()).isEqualTo(400);
		assertThat(call("POST", "/api/admin/amenities", admin, "{\"code\":\"has space\",\"name\":\"X\",\"categoryCode\":\"ROOM\"}")
				.status()).isEqualTo(400);

		String roof = "{\"code\":\"ROOF_ACCESS\",\"label\":\"Roof access\",\"fieldType\":\"DROPDOWN\","
				+ "\"options\":[{\"value\":\"NONE\",\"label\":\"None\"},\"SHARED\",\"PRIVATE\"]}";
		assertThat(call("POST", "/api/admin/property-fields", o, roof).status()).isEqualTo(403);
		Res roofRes = call("POST", "/api/admin/property-fields", admin, roof);
		assertThat(roofRes.status()).isEqualTo(200);
		assertThat(roofRes.body().get("options").get(1).get("label").asString()).isEqualTo("SHARED");
		assertThat(call("POST", "/api/admin/property-fields", admin, roof).status()).isEqualTo(400);
		assertThat(call("POST", "/api/admin/property-fields", admin,
				"{\"code\":\"NO_OPTS\",\"label\":\"x\",\"fieldType\":\"DROPDOWN\"}").status()).isEqualTo(400);
		assertThat(call("POST", "/api/admin/property-fields", admin,
				"{\"code\":\"TXT_OPTS\",\"label\":\"x\",\"fieldType\":\"TEXT\",\"options\":[\"a\"]}").status()).isEqualTo(400);
		assertThat(call("POST", "/api/admin/property-fields", admin,
				"{\"code\":\"BAD_RULE\",\"label\":\"x\",\"fieldType\":\"TEXT\",\"validationRules\":{\"min\":1}}").status())
				.isEqualTo(400);
		assertThat(call("POST", "/api/admin/property-fields", admin,
				"{\"code\":\"BAD_TYPE\",\"label\":\"x\",\"fieldType\":\"WIZARD\"}").status()).isEqualTo(400);
		assertThat(call("POST", "/api/admin/property-fields", admin,
				"{\"code\":\"TXT_REQ\",\"label\":\"Licence no.\",\"fieldType\":\"TEXT\","
						+ "\"validationRules\":{\"minLength\":3,\"maxLength\":8,\"pattern\":\"[A-Z0-9]+\"}}").status())
				.isEqualTo(200);

		String mapSauna = "{\"amenityCode\":\"SAUNA\",\"sortOrder\":20}";
		assertThat(call("POST", "/api/admin/property-types/PG/amenities", o, mapSauna).status()).isEqualTo(403);
		assertThat(call("POST", "/api/admin/property-types/NOPE/amenities", admin, mapSauna).status()).isEqualTo(404);
		assertThat(call("POST", "/api/admin/property-types/PG/amenities", admin, "{\"amenityCode\":\"GHOST\"}").status())
				.isEqualTo(400);
		assertThat(call("POST", "/api/admin/property-types/PG/amenities", admin, mapSauna).status()).isEqualTo(200);
		assertThat(call("POST", "/api/admin/property-types/PG/amenities", admin, mapSauna).status()).isEqualTo(200);
		assertThat(call("GET", "/api/config/property-types/PG/amenities", o, null).body().size()).isEqualTo(10);
		assertThat(call("POST", "/api/admin/property-types/PG/fields", admin, "{\"fieldCode\":\"ROOF_ACCESS\"}").status())
				.isEqualTo(200);
		assertThat(call("POST", "/api/admin/property-types/PG/fields", admin, "{\"fieldCode\":\"TXT_REQ\"}").status())
				.isEqualTo(200);
		assertThat(call("POST", "/api/admin/property-types/PG/fields", admin, "{\"fieldCode\":\"GHOST\"}").status())
				.isEqualTo(400);

		// ── a property's configuration ──
		String path = "/api/properties/" + pgId + "/configuration";
		Res cfg = call("GET", path, o, null);
		assertThat(cfg.status()).isEqualTo(200);
		assertThat(cfg.body().get("stayType").asString()).isEqualTo("PG");
		// Property level shows only PROPERTY / BOTH amenities (Bed, Mattress and Wardrobe are room-level).
		assertThat(cfg.body().get("amenities").size()).isEqualTo(7);
		assertThat(cfg.body().get("fields").size()).isEqualTo(5);
		assertThat(cfg.body().get("fields").get(0).get("value").isNull()).isTrue();

		// Tenant isolation: another owner, a resident-only route and anonymous callers are kept out.
		assertThat(call("GET", path, stranger, null).status()).isEqualTo(403);
		assertThat(call("PUT", path, stranger, "{\"amenities\":[]}").status()).isEqualTo(403);
		assertThat(call("GET", path, null, null).status()).isEqualTo(401);
		assertThat(call("GET", path, admin, null).status()).isEqualTo(200);
		assertThat(call("GET", "/api/properties/999999/configuration", admin, null).status()).isEqualTo(404);

		// Rejected input is reported and nothing is half-saved.
		String f = "\"fields\":";
		assertThat(call("PUT", path, o, "{\"amenities\":[{\"code\":\"BED\"}]}").status()).isEqualTo(400); // room-level only
		assertThat(call("PUT", path, o, "{\"amenities\":[{\"code\":\"LIFT\"}]}").status()).isEqualTo(400); // not a PG amenity
		assertThat(call("PUT", path, o, "{\"amenities\":[{\"code\":\"WIFI\"},{\"code\":\"wifi\"}]}").status()).isEqualTo(400);
		assertThat(call("PUT", path, o, "{" + f + "{\"GATE_CLOSING_TIME\":\"25:99\"}}").status()).isEqualTo(400);
		assertThat(call("PUT", path, o, "{" + f + "{\"VISITORS_ALLOWED\":\"yes\"}}").status()).isEqualTo(400);
		assertThat(call("PUT", path, o, "{" + f + "{\"MEAL_PLAN\":[\"BRUNCH\"]}}").status()).isEqualTo(400);
		assertThat(call("PUT", path, o, "{" + f + "{\"MEAL_PLAN\":\"LUNCH\"}}").status()).isEqualTo(400);
		assertThat(call("PUT", path, o, "{" + f + "{\"ROOF_ACCESS\":\"HELIPAD\"}}").status()).isEqualTo(400);
		assertThat(call("PUT", path, o, "{" + f + "{\"TXT_REQ\":\"ab\"}}").status()).isEqualTo(400);
		assertThat(call("PUT", path, o, "{" + f + "{\"TXT_REQ\":\"abc12\"}}").status()).isEqualTo(400);
		assertThat(call("PUT", path, o, "{" + f + "{\"STAR_RATING\":3}}").status()).isEqualTo(400); // a hotel field
		assertThat(call("GET", path, o, null).body().get("fields").get(0).get("value").isNull()).isTrue();

		// A full valid save; each field type lands in its own typed column and reads back as its own JSON type.
		Res saved = call("PUT", path, o, "{\"amenities\":[{\"code\":\"wifi\",\"notes\":\"100 Mbps\"},{\"code\":\"CCTV\"},"
				+ "{\"code\":\"FOOD\",\"enabled\":false}]," + f + "{\"GATE_CLOSING_TIME\":\"22:30\",\"VISITORS_ALLOWED\":true,"
				+ "\"MEAL_PLAN\":[\"BREAKFAST\",\"DINNER\",\"BREAKFAST\"],\"ROOF_ACCESS\":\"SHARED\",\"TXT_REQ\":\"AB123\"}}");
		assertThat(saved.status()).isEqualTo(200);
		java.util.Map<String, JsonNode> on = new java.util.HashMap<>();
		for (JsonNode a : saved.body().get("amenities")) {
			if (a.get("selected").asBoolean()) on.put(a.get("amenity").get("code").asString(), a);
		}
		assertThat(on.keySet()).containsExactlyInAnyOrder("WIFI", "CCTV");
		assertThat(on.get("WIFI").get("notes").asString()).isEqualTo("100 Mbps");
		java.util.Map<String, JsonNode> vals = new java.util.HashMap<>();
		for (JsonNode v : saved.body().get("fields")) {
			vals.put(v.get("field").get("code").asString(), v.get("value"));
		}
		assertThat(vals.get("GATE_CLOSING_TIME").asString()).isEqualTo("22:30");
		assertThat(vals.get("VISITORS_ALLOWED").booleanValue()).isTrue();
		assertThat(vals.get("MEAL_PLAN").size()).isEqualTo(2);
		assertThat(vals.get("ROOF_ACCESS").asString()).isEqualTo("SHARED");
		assertThat(vals.get("TXT_REQ").asString()).isEqualTo("AB123");

		// Omitting a part leaves it alone; the amenity list is the full selection; null clears a value.
		Res again = call("PUT", path, o, "{\"amenities\":[{\"code\":\"HOT_WATER\"}]," + f + "{\"VISITORS_ALLOWED\":null}}");
		assertThat(again.status()).isEqualTo(200);
		int selected = 0;
		for (JsonNode a : again.body().get("amenities")) {
			if (a.get("selected").asBoolean()) selected++;
		}
		assertThat(selected).isEqualTo(1);
		java.util.Map<String, JsonNode> after = new java.util.HashMap<>();
		for (JsonNode v : again.body().get("fields")) {
			after.put(v.get("field").get("code").asString(), v.get("value"));
		}
		assertThat(after.get("VISITORS_ALLOWED").isNull()).isTrue();
		assertThat(after.get("GATE_CLOSING_TIME").asString()).isEqualTo("22:30");

		// Numbers: whole-number and range rules come from the field definition.
		String aptPath = "/api/properties/" + aptId + "/configuration";
		assertThat(call("PUT", aptPath, oa, "{\"fields\":{\"PARKING_SLOTS\":-1}}").status()).isEqualTo(400);
		assertThat(call("PUT", aptPath, oa, "{\"fields\":{\"PARKING_SLOTS\":1.5}}").status()).isEqualTo(400);
		assertThat(call("PUT", aptPath, oa, "{\"fields\":{\"FURNISHING\":\"SEMI\"}}").status()).isEqualTo(400);
		Res apartment = call("PUT", aptPath, oa,
				"{\"amenities\":[{\"code\":\"LIFT\"},{\"code\":\"GYM\"}],\"fields\":{\"PARKING_SLOTS\":2,\"FURNISHING\":\"FURNISHED\"}}");
		assertThat(apartment.status()).isEqualTo(200);
		assertThat(apartment.body().get("fields").get(0).get("field").get("code").asString()).isEqualTo("FURNISHING");
		assertThat(apartment.body().get("fields").get(1).get("value").asLong()).isEqualTo(2);
		// A PG owner cannot see or touch the apartment.
		assertThat(call("GET", aptPath, o, null).status()).isEqualTo(403);

		// ── room level stays apart from the property level ──
		long roomId = call("POST", "/api/rooms", o, "{\"number\":\"CFG1\",\"capacity\":2,\"rent\":5000}").body().get("id")
				.asLong();
		String roomPath = "/api/properties/" + pgId + "/rooms/" + roomId + "/amenities";
		Res roomView = call("GET", roomPath, o, null);
		assertThat(roomView.status()).isEqualTo(200);
		assertThat(roomView.body().get("amenities").size()).isEqualTo(5); // Bed, Mattress, Wardrobe, Wi-Fi, Hot Water
		assertThat(call("PUT", roomPath, o, "{\"amenities\":[{\"code\":\"WARDROBE\"}]}").status()).isEqualTo(400); // Bed is required
		assertThat(call("PUT", roomPath, o, "{\"amenities\":[{\"code\":\"BED\"},{\"code\":\"CCTV\"}]}").status()).isEqualTo(400);
		Res roomSaved = call("PUT", roomPath, o, "{\"amenities\":[{\"code\":\"BED\"},{\"code\":\"WARDROBE\"}]}");
		assertThat(roomSaved.status()).isEqualTo(200);
		int roomOn = 0;
		for (JsonNode a : roomSaved.body().get("amenities")) {
			if (a.get("selected").asBoolean()) roomOn++;
		}
		assertThat(roomOn).isEqualTo(2);
		int propertyOn = 0;
		for (JsonNode a : call("GET", path, o, null).body().get("amenities")) {
			if (a.get("selected").asBoolean()) propertyOn++;
		}
		assertThat(propertyOn).isEqualTo(1); // unchanged by the room save
		assertThat(call("GET", roomPath, stranger, null).status()).isEqualTo(403);
		assertThat(call("GET", "/api/properties/" + pgId + "/rooms/999999/amenities", o, null).status()).isEqualTo(404);

		// Deleting the room removes its amenity rows (no foreign-key failure).
		assertThat(call("DELETE", "/api/rooms/" + roomId, o, null).status()).isLessThan(300);
		assertThat(call("GET", roomPath, o, null).status()).isEqualTo(404);

		// ── retiring and requiring, all from the admin side ──
		assertThat(call("POST", "/api/admin/property-types/PG/amenities", admin,
				"{\"amenityCode\":\"CCTV\",\"active\":false}").status()).isEqualTo(200);
		boolean cctvListed = false;
		for (JsonNode a : call("GET", path, o, null).body().get("amenities")) {
			if (a.get("amenity").get("code").asString().equals("CCTV")) cctvListed = true;
		}
		assertThat(cctvListed).isFalse();
		assertThat(call("POST", "/api/admin/property-types/PG/fields", admin,
				"{\"fieldCode\":\"TXT_REQ\",\"required\":true}").status()).isEqualTo(200);
		assertThat(call("PUT", path, o, "{\"fields\":{\"TXT_REQ\":null}}").status()).isEqualTo(400);
		assertThat(call("PUT", path, o, "{\"fields\":{\"TXT_REQ\":\"ZZ999\"}}").status()).isEqualTo(200);

		// A property type nobody mapped yet works the moment the admin maps it - no code involved.
		assertThat(call("POST", "/api/admin/lookups", admin,
				"{\"category\":\"STAY_TYPE\",\"code\":\"CAMPSITE\",\"label\":\"Campsite\",\"extra\":\"Tent\",\"sortOrder\":99}").status())
				.isEqualTo(200);
		assertThat(call("POST", "/api/admin/property-types/CAMPSITE/amenities", admin, "{\"amenityCode\":\"PARKING\"}")
				.status()).isEqualTo(200);
		assertThat(call("GET", "/api/config/property-types/CAMPSITE/amenities", o, null).body().size()).isEqualTo(1);
	}

	@Test
	void superAdminCanListEditAndHideCatalogEntries() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		createPg(admin, "Catalog PG", "ownerCatalog", "9000000691");
		String o = login("ownerCatalog", "OwnerPass#1");

		assertThat(call("GET", "/api/admin/amenities", o, null).status()).isEqualTo(403);
		assertThat(call("GET", "/api/admin/property-fields", o, null).status()).isEqualTo(403);

		long id = call("POST", "/api/admin/amenities", admin,
				"{\"code\":\"STEAM_ROOM\",\"name\":\"Steam room\",\"categoryCode\":\"RECREATION\"}").body().get("id").asLong();
		Res edited = call("PUT", "/api/admin/amenities/" + id, admin,
				"{\"name\":\"Steam Room\",\"applicableLevel\":\"PROPERTY\",\"icon\":\"cloud-outline\"}");
		assertThat(edited.status()).as(String.valueOf(edited.body())).isEqualTo(200);
		assertThat(edited.body().get("name").asString()).isEqualTo("Steam Room");
		assertThat(edited.body().get("applicableLevel").asString()).isEqualTo("PROPERTY");
		assertThat(call("PUT", "/api/admin/amenities/" + id, admin, "{\"categoryCode\":\"NOPE\"}").status()).isEqualTo(400);
		assertThat(call("PUT", "/api/admin/amenities/" + id, admin, "{\"name\":\" \"}").status()).isEqualTo(400);
		assertThat(call("PUT", "/api/admin/amenities/999999", admin, "{\"active\":false}").status()).isEqualTo(404);

		// Hidden entries vanish from the public catalog but stay in the admin list, and can come back.
		int publicBefore = call("GET", "/api/config/amenities", o, null).body().size();
		assertThat(call("PUT", "/api/admin/amenities/" + id, admin, "{\"active\":false}").status()).isEqualTo(200);
		assertThat(call("GET", "/api/config/amenities", o, null).body().size()).isEqualTo(publicBefore - 1);
		boolean listed = false;
		for (JsonNode a : call("GET", "/api/admin/amenities", admin, null).body()) {
			if (a.get("code").asString().equals("STEAM_ROOM") && !a.get("active").asBoolean()) listed = true;
		}
		assertThat(listed).isTrue();
		assertThat(call("POST", "/api/admin/property-types/PG/amenities", admin, "{\"amenityCode\":\"STEAM_ROOM\"}").status())
				.isEqualTo(400);
		assertThat(call("PUT", "/api/admin/amenities/" + id, admin, "{\"active\":true}").status()).isEqualTo(200);

		long fieldId = call("POST", "/api/admin/property-fields", admin,
				"{\"code\":\"PET_POLICY\",\"label\":\"Pets\",\"fieldType\":\"DROPDOWN\",\"options\":[\"ALLOWED\",\"NOT_ALLOWED\"]}")
				.body().get("id").asLong();
		Res field = call("PUT", "/api/admin/property-fields/" + fieldId, admin,
				"{\"label\":\"Pet policy\",\"defaultValue\":\"ALLOWED\",\"required\":true,\"sortOrder\":7}");
		assertThat(field.status()).isEqualTo(200);
		assertThat(field.body().get("label").asString()).isEqualTo("Pet policy");
		assertThat(field.body().get("fieldType").asString()).isEqualTo("DROPDOWN");
		assertThat(field.body().get("required").asBoolean()).isTrue();
		assertThat(call("PUT", "/api/admin/property-fields/" + fieldId, admin, "{\"defaultValue\":\"MAYBE\"}").status())
				.isEqualTo(400);
		assertThat(call("PUT", "/api/admin/property-fields/999999", admin, "{\"active\":false}").status()).isEqualTo(404);
		assertThat(call("PUT", "/api/admin/property-fields/" + fieldId, admin, "{\"active\":false}").status()).isEqualTo(200);
		boolean fieldListed = false;
		for (JsonNode f : call("GET", "/api/admin/property-fields", admin, null).body()) {
			if (f.get("code").asString().equals("PET_POLICY") && !f.get("active").asBoolean()) fieldListed = true;
		}
		assertThat(fieldListed).isTrue();
	}

	@Test
	void adminPicksAmenitiesWhenCreatingAndEditingAProperty() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		String body = "{\"name\":\"Pick PG\",\"stayType\":\"APARTMENT\",\"ownerUsername\":\"ownerPick\","
				+ "\"ownerMobile\":\"9000000701\",\"ownerPassword\":\"OwnerPass#1\",\"amenityCodes\":[\"LIFT\",\"gym\"]}";
		// an amenity of another type is refused and nothing is created
		assertThat(call("POST", "/api/admin/pgs", admin, body.replace("\"gym\"", "\"WIFI\"")).status()).isEqualTo(400);
		Res created = call("POST", "/api/admin/pgs", admin, body);
		assertThat(created.status()).isEqualTo(200);
		long id = created.body().get("id").asLong();
		// the older free-text column follows the selection, so the public listing keeps working
		assertThat(created.body().get("amenities").asString()).contains("Lift").contains("Gym");

		java.util.Set<String> on = new java.util.HashSet<>();
		call("GET", "/api/properties/" + id + "/configuration", admin, null).body().get("amenities")
				.forEach(a -> {
					if (a.get("selected").asBoolean()) {
						on.add(a.get("amenity").get("code").asString());
					}
				});
		assertThat(on).containsExactlyInAnyOrder("LIFT", "GYM");

		// editing replaces the selection; omitting the list leaves it alone
		Res edited = call("PUT", "/api/admin/pgs/" + id, admin,
				"{\"name\":\"Pick PG\",\"stayType\":\"APARTMENT\",\"amenityCodes\":[\"PARKING\"]}");
		assertThat(edited.status()).isEqualTo(200);
		assertThat(edited.body().get("amenities").asString()).isEqualTo("Parking");
		assertThat(call("PUT", "/api/admin/pgs/" + id, admin, "{\"name\":\"Pick PG 2\"}").body().get("amenities")
				.asString()).isEqualTo("Parking");
		assertThat(call("PUT", "/api/admin/pgs/" + id, admin, "{\"name\":\"Pick PG\",\"amenityCodes\":[]}").body()
				.get("amenities").asString()).isEmpty();
	}

	@Test
	void ownerAddsAPropertyWithTheFullProfile() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		createPg(admin, "Owner Home", "ownerFull", "9000000801");
		String o = login("ownerFull", "OwnerPass#1");
		Res added = call("POST", "/api/pg/properties", o, "{\"name\":\"Full Flats\",\"stayType\":\"APARTMENT\","
				+ "\"unitLabel\":\"Villa\",\"upiId\":\"full@upi\",\"upiName\":\"Full Owner\",\"mobile\":\"9111111111\","
				+ "\"startingPrice\":7500,\"description\":\"Nice\",\"locationUrl\":\"https://maps.example/x\","
				+ "\"amenityCodes\":[\"LIFT\",\"GYM\"]}");
		assertThat(added.status()).isEqualTo(200);
		long id = added.body().get("id").asLong();
		JsonNode pg = call("GET", "/api/admin/pgs/" + id, admin, null).body();
		assertThat(pg.get("unitLabel").asString()).isEqualTo("Villa");
		assertThat(pg.get("upiId").asString()).isEqualTo("full@upi");
		assertThat(pg.get("upiName").asString()).isEqualTo("Full Owner");
		assertThat(pg.get("mobile").asString()).isEqualTo("9111111111");
		assertThat(pg.get("startingPrice").decimalValue().intValue()).isEqualTo(7500);
		assertThat(pg.get("amenities").asString()).contains("Lift").contains("Gym");
		// amenities of another type are refused
		assertThat(call("POST", "/api/pg/properties", o,
				"{\"name\":\"Bad\",\"stayType\":\"APARTMENT\",\"amenityCodes\":[\"WIFI\"]}").status()).isEqualTo(400);
	}

	private Res upload(String path, String token, String contentType, byte[] data) throws Exception {
		String boundary = "----test" + System.nanoTime();
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f.bin\"\r\n"
				+ "Content-Type: " + contentType + "\r\n\r\n").getBytes());
		out.write(data);
		out.write(("\r\n--" + boundary + "--\r\n").getBytes());
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
		return new Res(r.statusCode(), r.body() == null || r.body().isBlank() ? null : json.readTree(r.body()));
	}

	@Test
	void anyRowCanCarryOneAttachmentThatIsReplacedAndRemoved() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		createPg(admin, "Attach PG", "ownerAttach", "9000000901");
		createPg(admin, "Attach Other", "ownerAttachX", "9000000902");
		String o = login("ownerAttach", "OwnerPass#1");
		String other = login("ownerAttachX", "OwnerPass#1");
		long id = call("POST", "/api/announcements", o, "{\"title\":\"Pool\",\"content\":\"Closed today\"}").body()
				.get("id").asLong();
		String path = "/api/attachments/announcement/" + id;
		byte[] png = { (byte) 0x89, 'P', 'N', 'G' };

		when(cloud.upload(any(), anyString())).thenReturn(new CloudinaryService.Stored("mystay/a1", "https://cdn.test/a1.png"),
				new CloudinaryService.Stored("mystay/a2", "https://cdn.test/a2.pdf"));

		assertThat(upload(path, null, "image/png", png).status()).isEqualTo(401);
		// nothing attached yet
		Res none = call("GET", path, o, null);
		assertThat(none.status()).isEqualTo(200);
		assertThat(none.body().get("url").isNull()).isTrue();

		Res first = upload(path, o, "image/png", png);
		assertThat(first.status()).isEqualTo(200);
		assertThat(first.body().get("url").asString()).isEqualTo("https://cdn.test/a1.png");
		assertThat(call("GET", path, o, null).body().get("id").asString()).isEqualTo("mystay/a1");

		// a second upload replaces the first and removes the old file from Cloudinary
		Res second = upload(path, o, "application/pdf", new byte[] { '%', 'P', 'D', 'F' });
		assertThat(second.body().get("url").asString()).isEqualTo("https://cdn.test/a2.pdf");
		verify(cloud).delete("mystay/a1", "https://cdn.test/a1.png");

		// rules: type, unknown entity, unknown row, and another property's row
		assertThat(upload(path, o, "text/plain", "hi".getBytes()).status()).isEqualTo(400);
		assertThat(call("GET", "/api/attachments/nothing/" + id, o, null).status()).isEqualTo(404);
		assertThat(call("GET", "/api/attachments/announcement/99999999", o, null).status()).isEqualTo(404);
		assertThat(call("GET", path, other, null).status()).isEqualTo(404);
		assertThat(upload(path, other, "image/png", png).status()).isEqualTo(404);
		assertThat(call("DELETE", path, other, null).status()).isEqualTo(404);

		assertThat(call("DELETE", path, o, null).status()).isEqualTo(204);
		verify(cloud).delete("mystay/a2", "https://cdn.test/a2.pdf");
		assertThat(call("GET", path, o, null).body().get("url").isNull()).isTrue();
	}

	@Test
	void propertyRoomAndResidentPicturesCanBeUploadedAndAreRemovedWithTheirOwner() throws Exception {
		String admin = login("superadmin", "DevAdmin#123");
		long pgA = createPg(admin, "Media PG", "ownerMedia", "9000000911");
		createPg(admin, "Media Other", "ownerMediaX", "9000000912");
		String o = login("ownerMedia", "OwnerPass#1");
		String other = login("ownerMediaX", "OwnerPass#1");
		byte[] png = { (byte) 0x89, 'P', 'N', 'G' };
		when(cloud.upload(any(), anyString())).thenReturn(
				new CloudinaryService.Stored("m/1", "https://cdn.test/1.png"),
				new CloudinaryService.Stored("m/2", "https://cdn.test/2.png"),
				new CloudinaryService.Stored("m/3", "https://cdn.test/3.png"),
				new CloudinaryService.Stored("m/4", "https://cdn.test/4.png"),
				new CloudinaryService.Stored("m/5", "https://cdn.test/5.png"),
				new CloudinaryService.Stored("m/6", "https://cdn.test/6.png"));

		// property: a gallery, owner or super admin only
		String prop = "/api/media/property/" + pgA;
		assertThat(upload(prop, null, "image/png", png).status()).isEqualTo(401);
		assertThat(upload(prop, o, "image/png", png).body().get("kind").asString()).isEqualTo("IMAGE");
		assertThat(upload(prop, admin, "image/png", png).status()).isEqualTo(200);
		assertThat(upload(prop, other, "image/png", png).status()).isEqualTo(403);
		assertThat(upload(prop, o, "text/plain", "hi".getBytes()).status()).isEqualTo(400);
		assertThat(call("GET", prop, o, null).body().size()).isEqualTo(2);
		assertThat(call("GET", prop, other, null).status()).isEqualTo(403);
		long firstImage = call("GET", prop, o, null).body().get(0).get("id").asLong();
		assertThat(call("DELETE", prop + "/" + firstImage, o, null).status()).isEqualTo(204);
		verify(cloud).delete("m/1", "https://cdn.test/1.png");
		assertThat(call("GET", prop, o, null).body().size()).isEqualTo(1);

		// room: a gallery, deleted with the room
		long roomId = call("POST", "/api/rooms", o, "{\"number\":\"M1\",\"capacity\":1,\"rent\":5000}").body().get("id")
				.asLong();
		String room = "/api/media/room/" + roomId;
		assertThat(upload(room, o, "image/png", png).status()).isEqualTo(200);
		assertThat(call("GET", room, other, null).status()).isEqualTo(404);
		assertThat(call("GET", "/api/media/room/99999999", o, null).status()).isEqualTo(404);

		// resident: one photo and one ID card, each replaced by a newer upload
		long residentId = call("POST", "/api/residents", o,
				"{\"name\":\"Pictured\",\"phone\":\"9000000913\",\"aadhaar\":\"123456789012\",\"roomId\":" + roomId + ",\"joinedOn\":\""
						+ LocalDate.now() + "\"}").body().get("id").asLong();
		String res = "/api/media/resident/" + residentId;
		assertThat(upload(res, o, "image/png", png).status()).isEqualTo(400); // kind is required
		assertThat(upload(res + "?kind=PHOTO", o, "image/png", png).status()).isEqualTo(200);
		assertThat(upload(res + "?kind=ID_CARD", o, "application/pdf", new byte[] { '%', 'P', 'D', 'F' }).status())
				.isEqualTo(200);
		assertThat(upload(res + "?kind=PHOTO", o, "application/pdf", new byte[] { '%', 'P', 'D', 'F' }).status())
				.isEqualTo(400); // a photo must be an image
		assertThat(call("GET", res, o, null).body().size()).isEqualTo(2);
		// a newer photo replaces the old one
		assertThat(upload(res + "?kind=PHOTO", o, "image/png", png).status()).isEqualTo(200);
		assertThat(call("GET", res, o, null).body().size()).isEqualTo(2);
		verify(cloud).delete("m/4", "https://cdn.test/4.png");

		// the resident's PHOTO (not the ID card) is the profile picture on every resident view
		JsonNode listedResident = null;
		for (JsonNode n : call("GET", "/api/residents", o, null).body()) {
			if (n.get("id").asLong() == residentId) listedResident = n;
		}
		assertThat(listedResident.get("photoUrl").asString()).isEqualTo("https://cdn.test/6.png");
		assertThat(call("GET", "/api/residents/" + residentId, o, null).body().get("photoUrl").asString())
				.isEqualTo("https://cdn.test/6.png");
		// Explore (no login needed) shows the property and room pictures, but never a resident's photo or ID card
		Res listed = call("GET", "/api/public/pgs", null, null);
		JsonNode summary = null;
		for (JsonNode n : listed.body()) {
			if (n.get("id").asLong() == pgA) summary = n;
		}
		assertThat(summary).isNotNull();
		assertThat(summary.get("images").size()).isEqualTo(1);
		assertThat(summary.get("images").get(0).asString()).isEqualTo("https://cdn.test/2.png");
		Res detail = call("GET", "/api/public/pgs/" + pgA, null, null);
		assertThat(detail.body().get("images").size()).isEqualTo(1);
		assertThat(detail.body().get("rooms").get(0).get("images").get(0).asString()).isEqualTo("https://cdn.test/3.png");
		assertThat(detail.body().toString()).doesNotContain("cdn.test/4.png").doesNotContain("cdn.test/5.png")
				.doesNotContain("cdn.test/6.png");

		// deleting the resident and the room removes their pictures from Cloudinary
		assertThat(call("DELETE", "/api/residents/" + residentId, o, null).status()).isEqualTo(204);
		verify(cloud).delete("m/5", "https://cdn.test/5.png");
		verify(cloud).delete("m/6", "https://cdn.test/6.png");
		assertThat(call("DELETE", "/api/rooms/" + roomId, o, null).status()).isEqualTo(204);
		verify(cloud).delete("m/3", "https://cdn.test/3.png");
	}
	@Test
	void uploadsAreRefusedUntilCloudinaryIsConfigured() {
		CloudinaryService unconfigured = new CloudinaryService("");
		assertThatThrownBy(() -> unconfigured.upload(new MockMultipartFile("file", "a.png", "image/png", new byte[] { 1 }), "x"))
				.hasMessageContaining("CLOUDINARY_URL");
		unconfigured.delete("anything", "https://x/image/upload/a.png"); // must not throw
	}
}