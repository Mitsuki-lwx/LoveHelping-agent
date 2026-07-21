package cn.lwx.lwxaiagent.tenant;

import cn.lwx.lwxaiagent.entity.User;
import cn.lwx.lwxaiagent.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户服务：注册 + 登录认证。
 * <p>
 * - 直接使用 UserMapper（MyBatis-Plus BaseMapper），不经过 Repository 层
 * - 建表在 @PostConstruct 中用 JdbcTemplate 初始化
 * - 密码用 BCrypt 加密
 */
@Slf4j
@Service
public class UserService {

    private static final String CREATE_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS users (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            username VARCHAR(50) NOT NULL UNIQUE,
            password VARCHAR(255) NOT NULL,
            tenant_id VARCHAR(50) NOT NULL DEFAULT 'default',
            role VARCHAR(20) NOT NULL DEFAULT 'USER',
            enabled BOOLEAN NOT NULL DEFAULT TRUE,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """;

    private final UserMapper userMapper;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Resource
    private JwtTokenProvider jwtTokenProvider;

    public UserService(UserMapper userMapper, JdbcTemplate jdbcTemplate) {
        this.userMapper = userMapper;
        this.jdbc = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_TABLE_SQL);
        log.info("Users table initialized");
    }

    public String register(String username, String password, String tenantId, String role) {
        if (userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0) {
            throw new RuntimeException("用户名已存在");
        }
        if (tenantId == null || tenantId.isBlank()) tenantId = "default";
        if (role == null || role.isBlank()) role = "USER";

        userMapper.insert(new User(username, passwordEncoder.encode(password), tenantId, role));
        log.info("User registered: username={}, tenant={}", username, tenantId);
        return jwtTokenProvider.generateToken(username, tenantId, role);
    }

    public String login(String username, String password) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) throw new RuntimeException("用户名或密码错误");

        if (!user.getEnabled()) throw new RuntimeException("账号已被禁用");
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }

        log.info("User logged in: username={}, tenant={}", username, user.getTenantId());
        return jwtTokenProvider.generateToken(user.getUsername(), user.getTenantId(), user.getRole());
    }
}
