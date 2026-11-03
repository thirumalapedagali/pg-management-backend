package com.pg;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
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
				"{\"name\":\"Asha\",\"phone\":\"9876543210\",\"roomId\":" + roomId + ",\"joinedOn\":\"" + today + "\"}");
		assertThat(res1.status()).isEqualTo(200);
		long residentId = res1.body().get("id").asLong();
		assertThat(res1.body().get("due").decimalValue().intValue()).isEqualTo(5000);
		assertThat(call("POST", "/api/residents", a, "{\"name\":\"Ravi\",\"roomId\":" + roomId + "}").status())
				.as("room is full").isEqualTo(400);

		// --- tenant isolation & global resident uniqueness by mobile ---
		assertThat(call("GET", "/api/rooms", b, null).body().size()).isZero();
		assertThat(call("GET", "/api/residents/" + residentId, b, null).status()).isEqualTo(404);
		assertThat(call("POST", "/api/residents", b, "{\"name\":\"X\",\"roomId\":" + roomId + "}").status())
				.as("cannot use another PG's room").isEqualTo(400);

		Res roomB = call("POST", "/api/rooms", b, "{\"number\":\"201\",\"capacity\":2,\"rent\":7000}");
		assertThat(roomB.status()).isEqualTo(200);
		long roomBId = roomB.body().get("id").asLong();

		// Resident Asha (9876543210) is already active in PG A, cannot be allocated in PG B or anywhere else
		assertThat(call("POST", "/api/residents", b,
				"{\"name\":\"Asha\",\"phone\":\"9876543210\",\"roomId\":" + roomBId + "}").status())
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
		Res newResInRoom1 = call("POST", "/api/residents", a, "{\"name\":\"Ravi\",\"roomId\":" + roomId + "}");
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
		assertThat(dash.body().get("beds").asInt()).isEqualTo(1);
		assertThat(dash.body().get("vacant").asInt()).isZero();
		assertThat(dash.body().get("months").size()).isEqualTo(6);
		assertThat(call("GET", "/api/dashboard/today", a, null).body().get("payments").size()).isEqualTo(1);

		// --- resident login & role limits ---
		Res ru = call("POST", "/api/users", a, "{\"username\":\"asha\",\"mobile\":\"9876543210\",\"password\":\"Resident#1\","
				+ "\"role\":\"RESIDENT\",\"residentId\":" + residentId + "}");
		assertThat(ru.status()).isEqualTo(200);
		assertThat(call("POST", "/api/users", a, "{\"username\":\"asha2\",\"mobile\":\"9876543299\",\"password\":\"Resident#1\","
				+ "\"role\":\"RESIDENT\",\"residentId\":" + residentId + "}").status()).as("one login per resident")
				.isEqualTo(400);
		assertThat(call("POST", "/api/users", a, "{\"username\":\"boss\",\"mobile\":\"9876543288\",\"password\":\"Password#1\","
				+ "\"role\":\"SUPER_ADMIN\"}").status()).as("cannot mint super admin").isEqualTo(400);

		String res = login("asha", "Resident#1");
		Res dues = call("GET", "/api/me/dues", res, null);
		assertThat(dues.status()).isEqualTo(200);
		assertThat(dues.body().get("resident").get("due").decimalValue().intValue()).isEqualTo(3000);
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
		long residentUserId = ru.body().get("id").asLong();
		assertThat(call("PUT", "/api/users/" + residentUserId, a, "{\"status\":\"BLOCKED\"}").status()).isEqualTo(200);
		assertThat(call("GET", "/api/me/dues", res, null).status()).isEqualTo(401);
		assertThat(call("POST", "/api/auth/login", null, "{\"username\":\"asha\",\"password\":\"Resident#1\"}").status())
				.isEqualTo(401);

		// --- expired licence blocks the PG's tokens ---
		long pgA = call("GET", "/api/admin/pgs", admin, null).body().get(0).get("id").asLong();
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
}
