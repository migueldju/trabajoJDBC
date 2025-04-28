package lsi.ubu.servicios;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lsi.ubu.excepciones.AlquilerCochesException;
import lsi.ubu.util.PoolDeConexiones;
import lsi.ubu.util.exceptions.SGBDError;
import lsi.ubu.util.exceptions.oracle.OracleSGBDErrorUtil;

public class ServicioImpl implements Servicio {
	private static final Logger LOGGER = LoggerFactory.getLogger(ServicioImpl.class);

	private static final int DIAS_DE_ALQUILER = 4;

	public void alquilar(String nifCliente, String matricula, Date fechaIni, Date fechaFin) throws SQLException {
		PoolDeConexiones pool = PoolDeConexiones.getInstance();

		Connection con = null;
		PreparedStatement st = null;
		ResultSet rs = null;

		/*
		 * El calculo de los dias se da hecho
		 */
		long diasDiff = DIAS_DE_ALQUILER;
		if (fechaFin != null) {
			diasDiff = TimeUnit.MILLISECONDS.toDays(fechaFin.getTime() - fechaIni.getTime());

			if (diasDiff < 1) {
				throw new AlquilerCochesException(AlquilerCochesException.SIN_DIAS);
			}
		}

		try {
			con = pool.getConnection();

			/* A completar por el alumnado... */

			/* ================================= AYUDA R PIDA ===========================*/
			/*
			 * Algunas de las columnas utilizan tipo numeric en SQL, lo que se traduce en
			 * BigDecimal para Java.
			 * 
			 * Convertir un entero en BigDecimal: new BigDecimal(diasDiff)
			 * 
			 * Sumar 2 BigDecimals: usar metodo "add" de la clase BigDecimal
			 * 
			 * Multiplicar 2 BigDecimals: usar metodo "multiply" de la clase BigDecimal
			 *
			 * 
			 * Paso de util.Date a sql.Date java.sql.Date sqlFechaIni = new
			 * java.sql.Date(sqlFechaIni.getTime());
			 *
			 *
			 * Recuerda que hay casos donde la fecha fin es nula, por lo que se debe de
			 * calcular sumando los dias de alquiler (ver variable DIAS_DE_ALQUILER) a la
			 * fecha ini.
			 */
			st = con.prepareStatement("SELECT NIF from clientes WHERE NIF = ?");
			st.setString(0, nifCliente);
			rs = st.executeQuery();
			if (!rs.next()) throw new AlquilerCochesException(AlquilerCochesException.CLIENTE_NO_EXIST);
			st.close();
			rs.close();
			
			st = con.prepareStatement("SELECT matricula from vehiculos WHERE matricula = ?");
			st.setString(0, matricula);
			rs = st.executeQuery();
			if (!rs.next()) throw new AlquilerCochesException(AlquilerCochesException.VEHICULO_NO_EXIST);
			st.close();
			rs.close();
			
			st = con.prepareStatement("SELECT matricula from reservas WHERE matricula = ? AND fecha_fin > ?");
			st.setString(0, matricula);
			st.setDate(1, new java.sql.Date(fechaIni.getTime()));
			rs = st.executeQuery();
			if (!rs.next()) throw new AlquilerCochesException(AlquilerCochesException.VEHICULO_OCUPADO);
			st.close();
			rs.close();
			
			st = con.prepareStatement("INSERT into reservas (idReserva, cliente, matricula, fecha_ini, fecha_fin) VALUES (idReserva.nextval, ?, ?, ?, ?)");
			st.setString(0, nifCliente);
			st.setString(1, matricula);
			st.setDate(2, new java.sql.Date(fechaIni.getTime()));
			st.setDate(3, new java.sql.Date(fechaFin.getTime()));
			st.executeUpdate();
			con.commit();
			

		} catch (SQLException e) {
			if (con!=null) con.rollback();
			if (e instanceof AlquilerCochesException) throw (AlquilerCochesException) e;
			if(new OracleSGBDErrorUtil().checkExceptionToCode(e, SGBDError.FK_VIOLATED)) {
				LOGGER.debug(e.getMessage());
				throw e;
			}

		} finally {
			if(st!= null) st.close();
			if(rs!=null) rs.close();
			if(con!=null) con.close();
		}
	}
}
