package io.quarkiverse.jimmer.it.kt.entity

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.OneToMany
import org.babyfish.jimmer.sql.Table
import java.util.UUID

@Entity
@Table(name = "kt_author")
interface KtAuthor {

	@Id
	val id: UUID

	val name: String

	@OneToMany(mappedBy = "author")
	val posts: List<KtPost>
}
