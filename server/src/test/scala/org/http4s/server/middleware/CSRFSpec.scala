package org.http4s.server.middleware

import org.http4s._
import scalaz._, Scalaz._
import scalaz.concurrent.Task
import scalaz.stream.Process._
import org.http4s.Uri.uri
import org.http4s.dsl._
import org.http4s.headers.{`Set-Cookie` => HCookie}

class CSRFSpec extends Http4sSpec {

  val dummyService: HttpService = HttpService {
    case GET -> Root =>
      Ok()
  }

  val dummyRequest = Request()

  "CSRFMiddleware" should {

    val newKey = CSRFMiddleware.generateSigningKey()
    val config = CSRFConfiguration(key = newKey)
    val csrf = CSRFMiddleware(config)

    "not validate different tokens" in {
      val equalCheck = for {
        t1 <- csrf.generateToken
        t2 <- csrf.generateToken
      } yield CSRFMiddleware.isEqual(t1, t2)

      equalCheck.unsafePerformSync must_== false
    }
    "validate for the correct csrf token" in {
      val response = (for {
        token <- csrf.generateToken
        res <- csrf.validate(dummyService)(
          dummyRequest
            .addCookie(Cookie(config.cookieName, token))
            .putHeaders(Header(config.headerName, token))
        )
      } yield res).unsafePerformSync.orNotFound

      response.status must_== Status.Ok
    }

    "not validate for token missing in cookie" in {
      val response = (for {
        token <- csrf.generateToken
        res <- csrf.validate(dummyService)(
          dummyRequest
            .putHeaders(Header(config.headerName, token))
        )
      } yield res).unsafePerformSync.orNotFound

      response.status must_== Status.Unauthorized
    }

    "not validate for token missing in header" in {
      val response = (for {
        token <- csrf.generateToken
        res <- csrf.validate(dummyService)(
          dummyRequest
            .addCookie(Cookie(config.cookieName, token))
        )
      } yield res).unsafePerformSync.orNotFound

      response.status must_== Status.Unauthorized
    }

    "not validate if token is missing in both" in {
      val response = csrf.validate(dummyService)(dummyRequest).unsafePerformSync

      response.orNotFound.status must_== Status.Unauthorized
    }

    "not validate for different tokens" in {
      val response = (for {
        token1 <- csrf.generateToken
        token2 <- csrf.generateToken
        res <- csrf.validate(dummyService)(
          dummyRequest
            .addCookie(Cookie(config.cookieName, token1))
            .putHeaders(Header(config.headerName, token2))
        )
      } yield res).unsafePerformSync.orNotFound

      response.status must_== Status.Unauthorized
    }

    "not return the same token to mitigate BREACH" in {
      val (response, originalToken) = (for {
        token <- csrf.generateToken
        res <- csrf.validate(dummyService)(
          dummyRequest
            .addCookie(Cookie(config.cookieName, token))
            .putHeaders(Header(config.headerName, token))
        )
        c <- Task.point(
          HCookie
            .from(res.orNotFound.headers)
            .map(_.cookie)
            .find(_.name == config.cookieName))
      } yield (c, token)).unsafePerformSync
      response.isDefined must_== true
      response.map(_.content) must_!= Some(originalToken)
    }

    "not return a token for a failed CSRF check" in {
      val response = (for {
        token <- csrf.generateToken
        res <- csrf.validate(dummyService)(dummyRequest)
      } yield res.orNotFound).unsafePerformSync

      response.status must_== Status.Unauthorized
      HCookie
        .from(response.headers)
        .map(_.cookie)
        .find(_.name == config.cookieName)
        .isEmpty must_== true
    }
  }

}