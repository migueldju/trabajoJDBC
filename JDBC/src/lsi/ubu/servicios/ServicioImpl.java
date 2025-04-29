package lsi.ubu.servicios;

import java.math.BigDecimal;
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
			
			// Utilizamos programación defensiva para cada caso
			// Si no existe el NIF del cliente en la base de datos, lanzamos excepción
			st = con.prepareStatement("SELECT NIF from clientes WHERE NIF = ?");
			st.setString(0, nifCliente);
			rs = st.executeQuery();
			if (!rs.next()) throw new AlquilerCochesException(AlquilerCochesException.CLIENTE_NO_EXIST);
			st.close();
			rs.close();
			
			// Si no existe la matrícula del vehículo en la base de datos, lanzamos excepción
			st = con.prepareStatement("SELECT matricula from vehiculos WHERE matricula = ?");
			st.setString(0, matricula);
			rs = st.executeQuery();
			if (!rs.next()) throw new AlquilerCochesException(AlquilerCochesException.VEHICULO_NO_EXIST);
			st.close();
			rs.close();
			
			// Si hay una reserva para el vehículo con la matŕicula correspondiente, lanzamos excepción (falta comprobar esto correctamente)
			st = con.prepareStatement("SELECT matricula from reservas WHERE matricula = ? AND fecha_fin > ?");
			st.setString(0, matricula);
			st.setDate(1, new java.sql.Date(fechaIni.getTime()));
			rs = st.executeQuery();
			if (rs.next()) throw new AlquilerCochesException(AlquilerCochesException.VEHICULO_OCUPADO);
			st.close();
			rs.close();
			
			// Insertamos nueva reserva (falta evaluar caso en caso de que fechaFin sea null)
			st = con.prepareStatement("INSERT into reservas (idReserva, cliente, matricula, fecha_ini, fecha_fin) VALUES (idReserva.nextval, ?, ?, ?, ?)");
			st.setString(0, nifCliente);
			st.setString(1, matricula);
			st.setDate(2, new java.sql.Date(fechaIni.getTime()));
			st.setDate(3, new java.sql.Date(fechaFin.getTime()));
			st.executeUpdate();
			con.commit();
			
			// Obtenemos datos del vehículo para generar factura
			st = con.prepareStatement(
					"SELECT m.precio_cada_dia, m.capacidad_deposito, m.tipo_combustible, pc.precio_por_litro, m.id_modelo " +
					"FROM vehiculos v JOIN modelos m ON v.id_modelo = m.id_modelo " +
					"JOIN precio_combustible pc ON m.tipo_combustible = pc.tipo_combustible " +
					"WHERE v.matricula = ?");
			st.setString(1, matricula);
			rs = st.executeQuery();
			
			// Almacenamos precios de alquiler y combustible
			if (rs.next()) {
				BigDecimal precioDia = rs.getBigDecimal(1);
				int capacidadDeposito = rs.getInt(2);
				String tipoCombustible = rs.getString(3);
				BigDecimal precioLitro = rs.getBigDecimal(4);
				int idModelo = rs.getInt(5);
				
				// Realizamos cálculos utilizando las operaciones necesarias con bigDecimal
				BigDecimal importeAlquiler = precioDia.multiply(new BigDecimal(diasDiff));
				BigDecimal importeCombustible = precioLitro.multiply(new BigDecimal(capacidadDeposito));
				BigDecimal importeTotal = importeAlquiler.add(importeCombustible);
				
				st = con.prepareStatement(
						"INSERT INTO facturas (nroFactura, importe, cliente) " +
						"VALUES (seq_num_fact.nextval, ?, ?)");
				st.setBigDecimal(1, importeTotal);
				st.setString(2, nifCliente);
				st.executeUpdate();
				st.close();
				
				// Obtenemos el número de factura generado para generar líneas
				st = con.prepareStatement("SELECT seq_num_fact.currval FROM dual");
				rs = st.executeQuery();
				rs.next();
				int nroFactura = rs.getInt(1);
				rs.close();
				st.close();
				
				// Añadimos línea de factura con coste alquiler
				st = con.prepareStatement(
						"INSERT INTO lineas_factura (nroFactura, concepto, importe) VALUES (?, ?, ?)");
				st.setInt(1, nroFactura);
				st.setString(2, diasDiff + " dias de alquiler, vehiculo modelo " + idModelo + "   ");
				st.setBigDecimal(3, importeAlquiler);
				st.executeUpdate();
				st.close();
				
				// Añadimos línea de factura con coste combustible
				st = con.prepareStatement(
						"INSERT INTO lineas_factura (nroFactura, concepto, importe) VALUES (?, ?, ?)");
				st.setInt(1, nroFactura);
				st.setString(2, "Deposito lleno de " + capacidadDeposito + " litros de " + tipoCombustible + " ");
				st.setBigDecimal(3, importeCombustible);
				st.executeUpdate();
			}
			
			// Confirmamos los cambios
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
