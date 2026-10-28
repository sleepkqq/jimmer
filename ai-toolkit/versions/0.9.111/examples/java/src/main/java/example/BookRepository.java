package example;

import java.util.List;
import org.babyfish.jimmer.spring.repository.JRepository;

public interface BookRepository extends JRepository<Book, Long> {
    List<Book> findByName(String name);
}
