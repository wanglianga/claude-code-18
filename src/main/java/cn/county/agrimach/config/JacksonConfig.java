package cn.county.agrimach.config;

import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 让 Jackson 正确处理 JPA 懒加载代理（未初始化关联输出 null，不触发 ByteBuddy 序列化错误） */
@Configuration
public class JacksonConfig {

    @Bean
    public Hibernate6Module hibernate6Module() {
        Hibernate6Module module = new Hibernate6Module();
        module.disable(Hibernate6Module.Feature.FORCE_LAZY_LOADING);
        module.enable(Hibernate6Module.Feature.SERIALIZE_IDENTIFIER_FOR_LAZY_NOT_LOADED_OBJECTS);
        return module;
    }
}
