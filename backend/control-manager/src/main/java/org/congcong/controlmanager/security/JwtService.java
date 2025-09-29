package org.congcong.controlmanager.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.congcong.controlmanager.config.AdminAuthProperties;
import org.congcong.controlmanager.entity.AdminUser;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;

@Service
public class JwtService {
    private final AdminAuthProperties props;
    private Algorithm algorithm() { return Algorithm.HMAC256(props.getJwt().getSecret()); }

    public JwtService(AdminAuthProperties props) { this.props = props; }

    public String issue(AdminUser user) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.getJwt().getTtlDays(), ChronoUnit.DAYS);
        String[] roles = user.getRoles() == null ? new String[]{} : Arrays.stream(user.getRoles().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        return JWT.create()
                // 设置用户唯一标识（通常是用户ID）
                .withSubject(String.valueOf(user.getId()))
                // 自定义 Claim：用户名
                .withClaim("name", user.getUsername())
                // 自定义 Claim：角色列表
                .withArrayClaim("roles", roles)
                // 自定义 Claim：是否必须修改密码
                .withClaim("mustChangePassword", user.isMustChangePassword())
                // 自定义 Claim：版本号（比如用于强制下线，验证 token 是否仍然有效）
                .withClaim("ver", user.getVer())
                // JWT 的唯一标识（JTI，防止重放攻击，可用于服务端黑名单）
                .withJWTId(UUID.randomUUID().toString())
                // 签发者（Issuer），一般写应用名或系统名
                .withIssuer(props.getJwt().getIssuer())
                // 签发时间（iat）
                .withIssuedAt(now)
                // 过期时间（exp）
                .withExpiresAt(exp)
                // 使用配置的算法和密钥进行签名
                .sign(algorithm());
    }

    public DecodedJWT verify(String token) {
        return JWT.require(algorithm())
                .withIssuer(props.getJwt().getIssuer())
                .build().verify(token);
    }
}