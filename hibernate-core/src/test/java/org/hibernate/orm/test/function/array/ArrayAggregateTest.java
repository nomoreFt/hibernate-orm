/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.orm.test.function.array;

import java.util.List;

import org.hibernate.boot.ResourceStreamLocator;
import org.hibernate.boot.spi.AdditionalMappingContributions;
import org.hibernate.boot.spi.AdditionalMappingContributor;
import org.hibernate.boot.spi.InFlightMetadataCollector;
import org.hibernate.boot.spi.MetadataBuildingContext;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.dialect.OracleArrayJdbcType;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.dialect.SpannerDialect;
import org.hibernate.engine.jdbc.Size;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.java.ArrayJavaType;
import org.hibernate.type.descriptor.java.spi.JavaTypeRegistry;
import org.hibernate.type.descriptor.jdbc.spi.JdbcTypeRegistry;
import org.hibernate.type.spi.TypeConfiguration;

import org.hibernate.testing.orm.domain.StandardDomainModel;
import org.hibernate.testing.orm.domain.gambit.EntityOfBasics;
import org.hibernate.testing.orm.junit.BootstrapServiceRegistry;
import org.hibernate.testing.orm.junit.DialectFeatureChecks;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.RequiresDialectFeature;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.hibernate.testing.orm.junit.SkipForDialect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author Christian Beikov
 */
@BootstrapServiceRegistry(
		javaServices = @BootstrapServiceRegistry.JavaService(
				role = AdditionalMappingContributor.class,
				impl = ArrayAggregateTest.UdtContributor.class
		)
)
// Make sure this stuff runs on a dedicated connection pool,
// otherwise we might run into ORA-21700: object does not exist or is marked for delete
// because the JDBC connection or database session caches something that should have been invalidated
@ServiceRegistry(settings = @Setting(name = AvailableSettings.CONNECTION_PROVIDER, value = ""))
@DomainModel(standardModels = StandardDomainModel.GAMBIT)
@SessionFactory
@RequiresDialectFeature(feature = DialectFeatureChecks.SupportsStructuralArrays.class)
@SkipForDialect(dialectClass = SpannerDialect.class, reason = "Doesn't support array_agg ordering yet")
@SkipForDialect(dialectClass = OracleDialect.class, majorVersion = 11, reason = "Oracle array_agg emulation requires json_arrayagg which was only added in Oracle 12")
public class ArrayAggregateTest {

	public static class UdtContributor implements AdditionalMappingContributor {
		@Override
		public void contribute(
				AdditionalMappingContributions contributions,
				InFlightMetadataCollector metadata,
				ResourceStreamLocator resourceStreamLocator,
				MetadataBuildingContext buildingContext) {
			final TypeConfiguration typeConfiguration = metadata.getTypeConfiguration();
			final JavaTypeRegistry javaTypeRegistry = typeConfiguration.getJavaTypeRegistry();
			final JdbcTypeRegistry jdbcTypeRegistry = typeConfiguration.getJdbcTypeRegistry();
			new OracleArrayJdbcType(
					jdbcTypeRegistry.getDescriptor( SqlTypes.VARCHAR ),
					"StringArray"
			).addAuxiliaryDatabaseObjects(
					new ArrayJavaType<>( javaTypeRegistry.getDescriptor( String.class ) ),
					Size.nil(),
					metadata.getDatabase(),
					typeConfiguration
			);
		}
	}

	@BeforeEach
	public void prepareData(SessionFactoryScope scope) {
		scope.inTransaction( em -> {
			final EntityOfBasics e1 = new EntityOfBasics( 1 );
			e1.setTheString( "abc" );
			final EntityOfBasics e2 = new EntityOfBasics( 2 );
			e2.setTheString( "def" );
			final EntityOfBasics e3 = new EntityOfBasics( 3 );
			em.persist( e1 );
			em.persist( e2 );
			em.persist( e3 );
		} );
	}

	@AfterEach
	public void cleanup(SessionFactoryScope scope) {
		scope.inTransaction( em -> {
			em.createMutationQuery( "delete from EntityOfBasics" ).executeUpdate();
		} );
	}

	@Test
	public void testEmpty(SessionFactoryScope scope) {
		scope.inSession( em -> {
			List<String[]> results = em.createQuery( "select array_agg(e.data) within group (order by e.id) from BasicEntity e", String[].class )
					.getResultList();
			assertEquals( 1, results.size() );
			assertNull( results.get( 0 ) );
		} );
	}

	@Test
	public void testWithoutNull(SessionFactoryScope scope) {
		scope.inSession( em -> {
			List<String[]> results = em.createQuery( "select array_agg(e.theString) within group (order by e.theString) from EntityOfBasics e where e.theString is not null", String[].class )
					.getResultList();
			assertEquals( 1, results.size() );
			assertArrayEquals( new String[]{ "abc", "def" }, results.get( 0 ) );
		} );
	}

	@Test
	public void testWithNull(SessionFactoryScope scope) {
		scope.inSession( em -> {
			List<String[]> results = em.createQuery( "select array_agg(e.theString) within group (order by e.theString asc nulls last) from EntityOfBasics e", String[].class )
					.getResultList();
			assertEquals( 1, results.size() );
			assertArrayEquals( new String[]{ "abc", "def", null }, results.get( 0 ) );
		} );
	}

	@Test
	public void testCompareAgainstArray(SessionFactoryScope scope) {
		scope.inSession( em -> {
			List<String[]> results = em.createQuery( "select 1 where array('abc','def',null) is not distinct from (select array_agg(e.theString) within group (order by e.theString asc nulls last) from EntityOfBasics e)", String[].class )
					.getResultList();
			assertEquals( 1, results.size() );
		} );
	}

}