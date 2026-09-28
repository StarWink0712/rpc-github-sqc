package helloserviceapi;

import lombok.*;

/**
 * Hello类表示要传递的信息
 *
 * @author 执笔画棠
 * @version 2025/10/14 13:43
 **/
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class Hello {
    private String message;
    private String description;
}
